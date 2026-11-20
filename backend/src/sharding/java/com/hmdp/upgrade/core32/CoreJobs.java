package com.hmdp.upgrade.core32;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.sql.Timestamp;
import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.upgrade.OrderMessages;
import com.hmdp.upgrade.DelayedClose;
import com.hmdp.upgrade.MqPoison;
import org.springframework.scheduling.annotation.Scheduled;
import com.hmdp.upgrade.Problem;

/** Only maintenance scans use physical tables; every business mutation returns to user-key SQL. */
public final class CoreJobs {
    public record Event(int version,String requestId,long userId,long activityId){}
    private final CoreEngine engine;private final OrderMessages messages;private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Value("${upgrade.core32.group:qupiao-core32-orders-v2}")private String group="qupiao-core32-orders-v2";
    @org.springframework.beans.factory.annotation.Value("${upgrade.core32.close-topic:qupiao-core32-close-v2}")private String closeTopic="qupiao-core32-close-v2";
    @org.springframework.beans.factory.annotation.Value("${upgrade.core32.close-group:qupiao-core32-close-v2}")private String closeGroup="qupiao-core32-close-v2";
    @jakarta.annotation.PostConstruct void subscribe(){if(enabled){recoverPendingMessages();messages.subscribe(topic,group,this::consume);messages.subscribe(closeTopic,closeGroup,this::consumeClose);}}
    /** Restart/cutover may repeat accepted work; request transactions make that safe. */
    public void recoverPendingMessages(){for(int shard=0;shard<32;shard++)engine.store.physical(shard).update("UPDATE "+CoreStore.table("cx_outbox",shard)+" SET sent_at=NULL,owner=NULL,lease_until=NULL,next_at=CURRENT_TIMESTAMP(3) WHERE request_id IN (SELECT id FROM "+CoreStore.table("cx_request",shard)+" WHERE state='ACCEPTED')");}
    private final String topic;private final boolean enabled;
    private int cursor;private long catalogCursor;public final LongAdder failures=new LongAdder();
    public CoreJobs(CoreEngine engine,OrderMessages messages,ObjectMapper json,String topic,boolean enabled){this.engine=engine;this.messages=messages;this.json=json;this.topic=topic;this.enabled=enabled;}
    boolean core(){return "CORE".equals(engine.stock.db.queryForObject("SELECT mode FROM ux_core_control WHERE id=1",String.class));}
    @Scheduled(fixedDelay=1000)public void tick(){if(!enabled)return;try{if(!core())return;}catch(RuntimeException failure){failures.increment();return;}
        attempt(this::profiles);attempt(this::catalog);attempt(this::channels);for(int i=0;i<4;i++){int shard=cursor++%32;attempt(()->maintain(shard,8));}attempt(this::orphans);
    }
    private int relayCursor;
    @Scheduled(fixedDelay=100)public void relayTick(){if(enabled)attempt(()->{if(core())relay(relayCursor++%32,2);});}
    private void attempt(Runnable action){try{action.run();}catch(RuntimeException failure){failures.increment();}}
    public void pump(int shard,int limit){
        relay(shard,Math.min(limit,4));
        maintain(shard,limit);
    }
    public void maintain(int shard,int limit){
        for(var row:engine.store.ready("cx_action",shard,"done_at IS NULL AND next_at<=CURRENT_TIMESTAMP(3) ORDER BY next_at",limit))try{engine.apply(row);}catch(RuntimeException failure){retry("cx_action","id",row);}
        for(var row:engine.store.ready("cx_request",shard,"state='ACCEPTED' AND deadline<=CURRENT_TIMESTAMP(3) ORDER BY deadline",limit))try{engine.expireRequest(CoreEngine.number(row,"user_id"),row.get("id").toString());}catch(RuntimeException failure){failures.increment();}
        sendCloses(shard,limit);
        for(var row:engine.store.ready("cx_refund",shard,"done_at IS NULL AND next_at<=CURRENT_TIMESTAMP(3) ORDER BY next_at",limit))try{engine.refund(row);}catch(RuntimeException failure){retry("cx_refund","channel_id",row);}
    }
    private void retry(String table,String key,Map<String,Object> row){failures.increment();long delay=Math.min(60,1L<<Math.min(5,((Number)row.getOrDefault("attempts",0)).intValue()+1));engine.db.update("UPDATE "+table+" SET attempts=attempts+1,next_at=? WHERE user_id=? AND "+key+"=? AND done_at IS NULL",Timestamp.from(Instant.now().plusSeconds(delay)),row.get("user_id"),row.get(key));}
    public void relay(int shard,int limit){
        for(var row:engine.store.ready("cx_outbox",shard,"sent_at IS NULL AND next_at<=CURRENT_TIMESTAMP(3) AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(3)) ORDER BY next_at",limit)){
            long user=CoreEngine.number(row,"user_id");String id=row.get("id").toString(),owner=UUID.randomUUID().toString();
            if(engine.db.update("UPDATE cx_outbox SET owner=?,lease_until=?,attempts=attempts+1 WHERE user_id=? AND id=? AND sent_at IS NULL AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(3))",owner,Timestamp.from(Instant.now().plusSeconds(15)),user,id)!=1)continue;
            try{
                var event=new Event(2,row.get("request_id").toString(),user,CoreEngine.number(row,"activity_id"));
                messages.send(topic,Long.toString(user),json.writeValueAsString(event)).get(6,TimeUnit.SECONDS);engine.fault.accept("core-send-before-mark");
                engine.db.update("UPDATE cx_outbox SET sent_at=CURRENT_TIMESTAMP(3),owner=NULL,lease_until=NULL WHERE user_id=? AND id=? AND owner=? AND sent_at IS NULL",user,id,owner);
            }catch(Exception failure){failures.increment();long delay=Math.min(60,1L<<Math.min(5,((Number)row.get("attempts")).intValue()+1));engine.db.update("UPDATE cx_outbox SET owner=NULL,lease_until=NULL,next_at=? WHERE user_id=? AND id=? AND owner=? AND sent_at IS NULL",Timestamp.from(Instant.now().plusSeconds(delay)),user,id,owner);if(failure instanceof InterruptedException)Thread.currentThread().interrupt();break;}
        }
    }
    public void consume(OrderMessages.Received record)throws Exception{
        if(!core())throw new IllegalStateException("Core trading is paused");
        Event event;
        try{event=json.readValue(record.body(),Event.class);if(event==null||event.version()!=2||event.userId()<=0||event.activityId()<=0||event.requestId()==null||!Long.toString(event.userId()).equals(record.key()))throw new IllegalArgumentException();}
        catch(Exception invalid){poison(record,"INVALID_CORE_EVENT");return;}
        try{engine.process(event.userId(),event.requestId(),event.activityId());}catch(Problem invalid){if(invalid.status!=400&&invalid.status!=404)throw invalid;poison(record,invalid.code);}
        engine.fault.accept("core-commit-before-ack");
    }
    void poison(OrderMessages.Received record,String reason){MqPoison.persist(engine.stock.db,record,reason);}
    public void channels(){
        for(var row:engine.stock.db.queryForList("SELECT * FROM ux_core_channel_claim WHERE processed_at IS NULL AND next_at<=CURRENT_TIMESTAMP(3) ORDER BY next_at LIMIT 10"))try{engine.receipt(CoreEngine.number(row,"user_id"),row.get("payment_id").toString(),row.get("channel_id").toString(),((Number)row.get("amount_cents")).intValue());}
        catch(RuntimeException failure){failures.increment();engine.stock.db.update("UPDATE ux_core_channel_claim SET attempts=attempts+1,next_at=TIMESTAMPADD(SECOND,LEAST(60,POW(2,LEAST(attempts,5))),CURRENT_TIMESTAMP(3)) WHERE channel_id=? AND processed_at IS NULL",row.get("channel_id"));}
    }
    public void sendCloses(int shard,int limit){
        for(var row:engine.store.ready("cx_close",shard,"done_at IS NULL AND message_sent_at IS NULL AND next_enqueue_at<=CURRENT_TIMESTAMP(3) ORDER BY next_enqueue_at",limit)){
            long user=CoreEngine.number(row,"user_id");String request=row.get("request_id").toString();
            try{messages.sendAt(closeTopic,request,json.writeValueAsString(new DelayedClose.Event(2,user,request)),CoreEngine.instant(row,"due_at")).get(6,TimeUnit.SECONDS);
                engine.fault.accept("core-close-send-before-mark");
                engine.db.update("UPDATE cx_close SET message_sent_at=CURRENT_TIMESTAMP(3) WHERE user_id=? AND request_id=? AND done_at IS NULL AND message_sent_at IS NULL",user,request);
            }catch(Exception failure){failures.increment();if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
                engine.db.update("UPDATE cx_close SET next_enqueue_at=? WHERE user_id=? AND request_id=? AND done_at IS NULL AND message_sent_at IS NULL",Timestamp.from(Instant.now().plusSeconds(5)),user,request);break;}
        }
    }
    public void consumeClose(OrderMessages.Received record)throws Exception{
        if(!core())throw new IllegalStateException("Core trading is paused");
        DelayedClose.Event event;
        try{event=json.readValue(record.body(),DelayedClose.Event.class);if(event==null||event.version()!=2||event.userId()<=0||event.requestId()==null||!event.requestId().equals(record.key()))throw new IllegalArgumentException();}
        catch(Exception invalid){poison(record,"INVALID_CORE_CLOSE_EVENT");return;}
        var tasks=engine.db.queryForList("SELECT done_at FROM cx_close WHERE user_id=? AND request_id=?",event.userId(),event.requestId());
        if(tasks.isEmpty()||tasks.get(0).get("done_at")!=null)return;
        if(!closeRequest(event.userId(),event.requestId()))throw new IllegalStateException("Order is not due yet");
        engine.fault.accept("core-close-commit-before-ack");
    }
    private boolean closeRequest(long user,String request){return engine.store.tx(user,()->{
        var order=engine.transition(user,request,"expire");if("PENDING_CONFIRM".equals(order.get("state")))return false;
        engine.db.update("UPDATE cx_close SET done_at=CURRENT_TIMESTAMP(3) WHERE user_id=? AND request_id=? AND done_at IS NULL",user,request);return true;
    });}
    public void reconcileCloses(int shard,int limit){for(var row:engine.store.ready("cx_close",shard,"done_at IS NULL AND due_at<=CURRENT_TIMESTAMP(3) ORDER BY due_at",limit))try{closeRequest(CoreEngine.number(row,"user_id"),row.get("request_id").toString());}catch(RuntimeException failure){failures.increment();}}
    @Scheduled(fixedDelayString="${upgrade.close-fallback-ms:60000}")public void closeFallback(){if(enabled)attempt(()->{if(core())for(int shard=0;shard<32;shard++){int current=shard;attempt(()->reconcileCloses(current,100));}});}
    private long activityCursor;
    public void profiles(){for(var row:engine.stock.db.queryForList("SELECT user_id,revision FROM ux_core_copy_user WHERE applied_revision<revision LIMIT 10")){long user=CoreEngine.number(row,"user_id");engine.user(user);engine.stock.db.update("UPDATE ux_core_copy_user SET applied_revision=? WHERE user_id=? AND revision=?",row.get("revision"),user,row.get("revision"));}}
    public void orphans(){
        var activities=engine.stock.db.queryForList("SELECT activity_id,epoch FROM ux_core_reservation_epoch WHERE activity_id>? ORDER BY activity_id LIMIT 8",activityCursor);
        if(activities.isEmpty()){activityCursor=0;return;}
        for(var row:activities){long activity=CoreEngine.number(row,"activity_id");try{var fields=engine.redis.expired(activity,20);if(fields!=null)for(String field:fields)engine.orphan(activity,field,row.get("epoch").toString());activityCursor=activity;}catch(RuntimeException failure){failures.increment();break;}}
    }
    public void catalog(){
        var rows=engine.stock.db.queryForList("SELECT a.*,v.shop_id,v.title offer_title,s.name shop_name,f.image_path FROM ux_activity a LEFT JOIN ux_offer v ON v.activity_id=a.id LEFT JOIN ux_shop s ON s.id=v.shop_id LEFT JOIN ux_storefront f ON f.shop_id=s.id WHERE a.id>? ORDER BY a.id LIMIT 100",catalogCursor);
        if(rows.isEmpty()){catalogCursor=0;return;}
        for(var r:rows){engine.db.update("INSERT INTO cx_event(activity_id,shop_id,shop_name,offer_title,image_path,price_cents,starts_at,ends_at) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE shop_id=VALUES(shop_id),shop_name=VALUES(shop_name),offer_title=VALUES(offer_title),image_path=VALUES(image_path),price_cents=VALUES(price_cents),starts_at=VALUES(starts_at),ends_at=VALUES(ends_at)",r.get("id"),r.get("shop_id"),r.get("shop_name"),r.get("offer_title"),r.get("image_path"),r.get("price_cents"),r.get("starts_at"),r.get("ends_at"));catalogCursor=CoreEngine.number(r,"id");}
    }
}
