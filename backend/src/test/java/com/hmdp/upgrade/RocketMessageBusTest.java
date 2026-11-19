package com.hmdp.upgrade;

import java.time.Instant;
import java.util.concurrent.ExecutionException;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RocketMessageBusTest {
    DefaultMQProducer producer=mock(DefaultMQProducer.class);
    RocketMessageBus bus(){var bus=new RocketMessageBus("127.0.0.1:39876","test-producer",false);ReflectionTestUtils.setField(bus,"producer",producer);ReflectionTestUtils.setField(bus,"running",true);return bus;}
    @Test void unsuccessfulPersistenceAcknowledgementMustNotMarkOutboxSent()throws Exception{
        var bus=bus();doAnswer(call->{var result=new SendResult();result.setSendStatus(SendStatus.FLUSH_DISK_TIMEOUT);call.<SendCallback>getArgument(1).onSuccess(result);return null;}).when(producer).send(any(Message.class),any(SendCallback.class),eq(5000L));
        assertThrows(ExecutionException.class,()->bus.send("orders","request","body").get());
    }
    @Test void delayUsesAbsoluteDeadlineAndOverdueWorkIsSentImmediately()throws Exception{
        var bus=bus();var future=Instant.now().plusSeconds(300);var seen=new java.util.ArrayList<Message>();
        doAnswer(call->{seen.add(call.getArgument(0));var result=new SendResult();result.setSendStatus(SendStatus.SEND_OK);call.<SendCallback>getArgument(1).onSuccess(result);return null;}).when(producer).send(any(Message.class),any(SendCallback.class),eq(5000L));
        bus.sendAt("close","request","body",future).get();bus.sendAt("close","request","body",Instant.now().minusSeconds(1)).get();
        assertEquals(future.toEpochMilli(),seen.get(0).getDeliverTimeMs());assertEquals(0,seen.get(1).getDeliverTimeMs());
    }
    @Test void disconnectedClientAndFailedCallbackAreRetryableFailures()throws Exception{
        assertThrows(ExecutionException.class,()->new RocketMessageBus("127.0.0.1:39876","not-started",false).send("orders","request","body").get());
        var bus=bus();doAnswer(call->{call.<SendCallback>getArgument(1).onException(new IllegalStateException("network lost"));return null;}).when(producer).send(any(Message.class),any(SendCallback.class),eq(5000L));
        assertThrows(ExecutionException.class,()->bus.send("orders","request","body").get());
    }
    @Test void existingTopicIsNeverResizedByDevelopmentBootstrap()throws Exception{
        var bus=bus();when(producer.fetchPublishMessageQueues("existing")).thenReturn(java.util.List.of(new org.apache.rocketmq.common.message.MessageQueue("existing","broker",0)));
        bus.ensureTopic("existing");verify(producer,never()).createTopic(anyString(),anyString(),anyInt(),anyMap());
    }
    @Test void networkFailureIsNotTreatedAsMissingTopic()throws Exception{
        var bus=bus();when(producer.fetchPublishMessageQueues("orders")).thenThrow(new org.apache.rocketmq.client.exception.MQClientException("network offline",new java.io.IOException()));
        assertThrows(org.apache.rocketmq.client.exception.MQClientException.class,()->bus.ensureTopic("orders"));verify(producer,never()).createTopic(anyString(),anyString(),anyInt(),anyMap());
    }
}
