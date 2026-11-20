package com.hmdp.upgrade;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.LongAdder;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.FunctionCounter;

/** RocketMQ 5.x remoting client; broker must enable the timestamp timer service. */
@Component
public final class RocketMessageBus implements OrderMessages,SmartLifecycle,MeterBinder {
    private record Subscription(String topic,String group,Handler handler) {}
    private final String nameserver;private final boolean jobs;
    private final DefaultMQProducer producer;
    private final List<Subscription> subscriptions=new ArrayList<>();
    private final List<DefaultMQPushConsumer> consumers=new ArrayList<>();
    private volatile boolean running;
    @Value("${upgrade.rocketmq.retry-delay-level:3}")private int retryDelayLevel=3;
    @Value("${upgrade.rocketmq.create-topics:true}")private boolean createTopics=true;
    public final LongAdder retries=new LongAdder();
    @jakarta.annotation.PostConstruct void validateSettings(){if(retryDelayLevel<1||retryDelayLevel>18)throw new IllegalArgumentException("RocketMQ retry delay level must be 1..18");}
    public RocketMessageBus(@Value("${upgrade.rocketmq.nameserver:127.0.0.1:39876}")String nameserver,
        @Value("${upgrade.rocketmq.producer-group:qupiao-producer}")String group,
        @Value("${upgrade.jobs:true}")boolean jobs) {
        this.nameserver=nameserver;this.jobs=jobs;producer=new DefaultMQProducer(group);
        producer.setNamesrvAddr(nameserver);producer.setInstanceName(UUID.randomUUID().toString());
        producer.setPollNameServerInterval(5000);
        producer.setSendMsgTimeout(5000);producer.setRetryTimesWhenSendAsyncFailed(0);
    }
    @Override public synchronized void subscribe(String topic,String group,Handler handler) {
        if(running)throw new IllegalStateException("Register consumers before starting RocketMQ");
        if(subscriptions.stream().anyMatch(s->s.group().equals(group)))throw new IllegalArgumentException("Consumer groups must be distinct");
        subscriptions.add(new Subscription(topic,group,handler));
    }
    @Override public CompletableFuture<Void> send(String topic,String key,String body){return sendAt(topic,key,body,null);}
    @Override public CompletableFuture<Void> sendAt(String topic,String key,String body,Instant due) {
        var future=new CompletableFuture<Void>();
        if(!running){future.completeExceptionally(new IllegalStateException("RocketMQ is not running"));return future;}
        var message=new Message(topic,"",key,body.getBytes(StandardCharsets.UTF_8));
        // An overdue task is sent immediately; only the database decides whether it may close.
        if(due!=null&&due.toEpochMilli()>System.currentTimeMillis())message.setDeliverTimeMs(due.toEpochMilli());
        try {producer.send(message,new SendCallback(){
            @Override public void onSuccess(SendResult result){
                if(result.getSendStatus()==SendStatus.SEND_OK)future.complete(null);
                else future.completeExceptionally(new IllegalStateException("RocketMQ persistence acknowledgement: "+result.getSendStatus()));
            }
            @Override public void onException(Throwable failure){future.completeExceptionally(failure);}
        },5000);}catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();future.completeExceptionally(failure);}
        return future;
    }
    @Override public synchronized void start(){
        if(running)return;
        try {
            producer.start();
            if(jobs&&createTopics)for(var subscription:subscriptions)ensureTopic(subscription.topic());
            if(jobs)for(var subscription:subscriptions){
                var consumer=new DefaultMQPushConsumer(subscription.group());consumers.add(consumer);
                consumer.setNamesrvAddr(nameserver);consumer.setInstanceName(UUID.randomUUID().toString());
                consumer.setPollNameServerInterval(5000);
                consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
                consumer.setConsumeThreadMin(2);consumer.setConsumeThreadMax(4);
                consumer.setConsumeMessageBatchMaxSize(1);consumer.setMaxReconsumeTimes(Integer.MAX_VALUE);
                consumer.subscribe(subscription.topic(),"*");
                consumer.registerMessageListener((MessageListenerConcurrently)(messages,context)->{
                    try {for(var message:messages)subscription.handler().handle(new Received(subscription.topic(),message.getBrokerName(),
                        message.getQueueId(),message.getQueueOffset(),message.getKeys(),new String(message.getBody(),StandardCharsets.UTF_8),message.getBornTimestamp()));
                        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                    }catch(Exception failure){retries.increment();context.setDelayLevelWhenNextConsume(retryDelayLevel);return ConsumeConcurrentlyStatus.RECONSUME_LATER;}
                });consumer.start();
            }
            running=true;
        }catch(Exception failure){stop();throw new IllegalStateException("Could not start RocketMQ clients",failure);}
    }
    @Override public synchronized void stop(){running=false;consumers.forEach(DefaultMQPushConsumer::shutdown);consumers.clear();producer.shutdown();}
    /** Development bootstrap only; never resize an existing topic. Production provisions topics separately. */
    void ensureTopic(String topic)throws Exception{
        try{producer.fetchPublishMessageQueues(topic);return;}
        catch(MQClientException failure){
            boolean missing=false;for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof MQClientException mq&&mq.getResponseCode()==ResponseCode.TOPIC_NOT_EXIST)missing=true;
            if(!missing)throw failure;
        }
        producer.createTopic("TBW102",topic,4,Map.of());
        // The consumer must see the new route before it starts its initial registration.
        for(int attempt=0;;attempt++)try{producer.fetchPublishMessageQueues(topic);return;}catch(MQClientException notVisible){if(attempt>=19)throw notVisible;Thread.sleep(250);}
    }
    @Override public boolean isRunning(){return running;}
    @Override public int getPhase(){return Integer.MAX_VALUE-1000;}
    @Override public void bindTo(MeterRegistry registry){FunctionCounter.builder("life.mq.consumer.retries",retries,LongAdder::doubleValue).register(registry);}
}
