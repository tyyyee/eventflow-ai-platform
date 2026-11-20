package com.hmdp.upgrade.core32;

import com.hmdp.upgrade.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.function.Consumer;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Primary requests/orders live in user shards. Cross-store stock effects have durable grants and actions. */
public final class CoreEngine {
    public final CoreStore store;public final JdbcTemplate db;public final CoreStock stock;public final CoreRedis redis;
    private final GlobalIds ids;private final Transactions sourceTx;
    Consumer<String> fault=point->{};
    public CoreEngine(CoreStore store,CoreStock stock,CoreRedis redis,GlobalIds ids,Transactions sourceTx){this.store=store;this.db=store.db();this.stock=stock;this.redis=redis;this.ids=ids;this.sourceTx=sourceTx;}
    public String id(){return Long.toString(ids.nextId());}
    public void requireCore(){String mode=stock.db.queryForObject("SELECT mode FROM ux_core_control WHERE id=1",String.class);if(!"CORE".equals(mode))throw new Problem(503,"CORE_WRITES_PAUSED");}
    static long number(Map<String,Object> row,String field){return ((Number)row.get(field)).longValue();}
    static Instant instant(Map<String,Object> row,String field){return ((Timestamp)row.get(field)).toInstant();}
    static String hash(long activity){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("v1:activity:"+activity).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public void user(long user){
        if(user<=0)throw new Problem(401,"UNAUTHORIZED");
        if(db.queryForObject("SELECT COUNT(*) FROM cx_user WHERE user_id=?",Integer.class,user)>0)return;
        var account=stock.db.queryForList("SELECT username,display_name,created_at FROM ux_account WHERE id=?",user);if(account.isEmpty())throw new Problem(401,"ACCOUNT_NOT_FOUND");
        var row=account.get(0);db.update("INSERT IGNORE INTO cx_user(user_id,username,display_name,created_at) VALUES(?,?,?,?)",user,row.get("username"),row.get("display_name"),row.get("created_at"));
    }
    void lockUser(long user){db.queryForObject("SELECT user_id FROM cx_user WHERE user_id=? FOR UPDATE",Long.class,user);}
    public void prepare(long activity){sourceTx.run(()->{
        var rows=stock.db.queryForList("SELECT * FROM ux_activity WHERE id=? FOR UPDATE",activity);if(rows.isEmpty())throw new Problem(404,"ACTIVITY_NOT_FOUND");
        if(stock.db.queryForObject("SELECT COUNT(*) FROM ux_core_reservation_epoch WHERE activity_id=?",Integer.class,activity)>0)return null;
        for(int shard=0;shard<32;shard++)if(store.physical(shard).queryForObject("SELECT COUNT(*) FROM "+CoreStore.table("cx_request",shard)+" WHERE activity_id=?",Integer.class,activity)>0)throw new Problem(409,"RESERVATION_RECOVERY_REQUIRED");
        String epoch=UUID.randomUUID().toString();redis.prepare(activity,((Number)rows.get(0).get("available")).intValue(),epoch);epoch=redis.epoch(activity);if(epoch==null)throw new Problem(503,"RESERVATION_RECOVERY_REQUIRED");
        stock.db.update("INSERT INTO ux_core_reservation_epoch(activity_id,epoch) VALUES(?,?)",activity,epoch);return null;
    });}
    public Map<String,Object> existing(long user,String key){var rows=db.queryForList("SELECT * FROM cx_request WHERE user_id=? AND request_key=?",user,key);return rows.isEmpty()?null:rows.get(0);}
    public Map<String,Object> request(long user,String request){var rows=db.queryForList("SELECT * FROM cx_request WHERE user_id=? AND id=?",user,request);if(rows.isEmpty())throw new Problem(404,"REQUEST_NOT_FOUND");return rows.get(0);}
    public record Request(String id,String userId,long activityId,String hash,String state,String reason,Instant deadline){}
    public Request response(Map<String,Object> r){return new Request(r.get("id").toString(),r.get("user_id").toString(),number(r,"activity_id"),hash(number(r,"activity_id")),r.get("state").toString(),(String)r.get("reason"),instant(r,"deadline"));}
    public Request purchase(long user,String key,long activity){
        requireCore();if(activity<=0||key==null||!key.matches("[A-Za-z0-9_-]{8,64}"))throw new Problem(400,"INVALID_REQUEST");user(user);
        var previous=existing(user,key);if(previous!=null){if(number(previous,"activity_id")!=activity)throw new Problem(409,"IDEMPOTENCY_CONFLICT");return response(previous);}
        var a=stock.activity(activity);Instant now=stock.now().toInstant();if(now.isBefore(instant(a,"starts_at"))||!now.isBefore(instant(a,"ends_at")))throw new Problem(409,"ACTIVITY_CLOSED");
        prepare(activity);var reservation=redis.reserve(activity,user,key);
        if(reservation.code()==2)throw new Problem(409,"ALREADY_PURCHASED");if(reservation.code()==3)throw new Problem(409,"SOLD_OUT");
        fault.accept("core-reserve-before-request");
        return store.tx(user,()->{
            lockUser(user);var found=existing(user,key);if(found!=null){if(number(found,"activity_id")!=activity)throw new Problem(409,"IDEMPOTENCY_CONFLICT");return response(found);}
            if(reservation.code()>=13)throw new Problem(409,"REQUEST_TERMINAL");
            String request=id(),order=id();Instant deadline=now.plusSeconds(120);if(instant(a,"process_until").isBefore(deadline))deadline=instant(a,"process_until");
            db.update("INSERT INTO cx_request(id,user_id,request_key,activity_id,order_id,state,created_at,deadline,reservation_epoch) VALUES(?,?,?,?,?,'ACCEPTED',?,?,?)",request,user,key,activity,order,Timestamp.from(now),Timestamp.from(deadline),reservation.epoch());
            db.update("INSERT INTO cx_outbox(id,user_id,request_id,activity_id,created_at,next_at) VALUES(?,?,?,?,?,?)",id(),user,request,activity,Timestamp.from(now),Timestamp.from(now));
            return response(request(user,request));
        });
    }
    public Map<String,Object> order(long user,String request){var rows=db.queryForList("SELECT * FROM cx_order WHERE user_id=? AND request_id=?",user,request);if(rows.isEmpty())throw new Problem(404,"ORDER_NOT_FOUND");return rows.get(0);}
    void action(long user,String request,String kind){db.update("INSERT IGNORE INTO cx_action(id,user_id,request_id,kind,next_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP(3))",id(),user,request,kind);}
    void finish(long user,String request,String state,String reason){db.update("UPDATE cx_request SET state=?,reason=?,completed_at=CURRENT_TIMESTAMP(3) WHERE user_id=? AND id=? AND state='ACCEPTED'",state,reason,user,request);}
    public Request process(long user,String request,long activity){
        requireCore();var completed=store.tx(user,()->{
            db.queryForList("SELECT id FROM cx_request WHERE user_id=? AND id=? FOR UPDATE",user,request);var r=request(user,request);
            if(number(r,"activity_id")!=activity)throw new Problem(400,"EVENT_MISMATCH");if(!r.get("state").equals("ACCEPTED"))return response(r);
            if(!stock.now().toInstant().isBefore(instant(r,"deadline"))){finish(user,request,"EXPIRED","PROCESS_DEADLINE");action(user,request,"RELEASE");return response(request(user,request));}
            int price;
            try{price=stock.hold(user,request,activity);}catch(Problem rejected){if(!List.of("SOLD_OUT","ALREADY_PURCHASED").contains(rejected.code))throw rejected;finish(user,request,"REJECTED",rejected.code);action(user,request,"RELEASE");return response(request(user,request));}
            fault.accept("core-after-stock-hold");Timestamp now=stock.now(),until=Timestamp.from(now.toInstant().plusSeconds(300));
            db.update("INSERT INTO cx_order(id,user_id,request_id,activity_id,price_cents,state,confirm_until,created_at) VALUES(?,?,?,?,?,'PENDING_CONFIRM',?,?)",r.get("order_id"),user,request,activity,price,until,now);
            db.update("INSERT INTO cx_close(request_id,user_id,due_at) VALUES(?,?,?)",request,user,until);
            finish(user,request,"SUCCEEDED",null);action(user,request,"CONFIRM");action(user,request,"INDEX");action(user,request,"LOG_CREATED");
            return response(request(user,request));
        });fault.accept("core-order-commit-before-ack");return completed;
    }
    public Map<String,Object> transition(long user,String request,String action){return store.tx(user,()->transitionLocked(user,request,action,false));}
    Map<String,Object> transitionLocked(long user,String request,String action,boolean payment){
        db.queryForList("SELECT id FROM cx_request WHERE user_id=? AND id=? FOR UPDATE",user,request);request(user,request);var order=order(user,request);
        if(!order.get("state").equals("PENDING_CONFIRM"))return order;
        boolean expired=!stock.now().toInstant().isBefore(instant(order,"confirm_until"));if(action.equals("expire")&&!expired)return order;
        if(!List.of("confirm","cancel","expire").contains(action))throw new Problem(400,"INVALID_ACTION");
        if(action.equals("confirm")&&!payment&&db.queryForObject("SELECT COUNT(*) FROM cx_payment WHERE user_id=? AND request_id=?",Integer.class,user,request)>0)throw new Problem(409,"PAYMENT_REQUIRED");
        String next=expired?"EXPIRED":action.equals("confirm")?"CONFIRMED":"CANCELLED";
        if(db.update("UPDATE cx_order SET state=? WHERE user_id=? AND id=? AND state='PENDING_CONFIRM'",next,user,order.get("id"))==1){
            db.update("UPDATE cx_close SET done_at=CURRENT_TIMESTAMP(3) WHERE user_id=? AND request_id=? AND done_at IS NULL",user,request);
            if(!next.equals("CONFIRMED"))action(user,request,"RESTORE");action(user,request,"LOG_"+next);
        }
        return order(user,request);
    }
    public Map<String,Object> createPayment(long user,String request){return store.tx(user,()->{
        db.queryForList("SELECT id FROM cx_request WHERE user_id=? AND id=? FOR UPDATE",user,request);var order=order(user,request);
        var prior=db.queryForList("SELECT * FROM cx_payment WHERE user_id=? AND request_id=?",user,request);if(!prior.isEmpty())return payment(user,prior.get(0).get("id").toString());
        if(!order.get("state").equals("PENDING_CONFIRM")||!stock.now().toInstant().isBefore(instant(order,"confirm_until")))throw new Problem(409,"ORDER_NOT_PAYABLE");
        String id=id();db.update("INSERT INTO cx_payment(id,user_id,request_id,amount_cents,state,created_at) VALUES(?,?,?,?,'OPEN',CURRENT_TIMESTAMP(3))",id,user,request,order.get("price_cents"));return payment(user,id);
    });}
    public Map<String,Object> payment(long user,String id){var rows=db.queryForList("SELECT * FROM cx_payment WHERE user_id=? AND id=?",user,id);if(rows.isEmpty())throw new Problem(404,"PAYMENT_NOT_FOUND");var row=rows.get(0);row.put("receipts",db.queryForList("SELECT channel_id,amount_cents,outcome FROM cx_receipt WHERE user_id=? AND payment_id=? ORDER BY created_at,channel_id",user,id));return row;}
    public Map<String,Object> receipt(long user,String payment,String channel,int amount){
        var initial=payment(user,payment);if(number(initial,"amount_cents")!=amount)throw new Problem(400,"PAYMENT_AMOUNT_MISMATCH");
        stock.channel(user,payment,channel,amount);
        fault.accept("core-channel-before-receipt");
        var result=store.tx(user,()->{
            String request=initial.get("request_id").toString();db.queryForList("SELECT id FROM cx_request WHERE user_id=? AND id=? FOR UPDATE",user,request);
            var prior=db.queryForList("SELECT * FROM cx_receipt WHERE user_id=? AND channel_id=?",user,channel);if(!prior.isEmpty()){if(!prior.get(0).get("payment_id").equals(payment))throw new Problem(409,"PAYMENT_RECEIPT_CONFLICT");return prior.get(0);}
            var p=payment(user,payment);var before=order(user,request);boolean open=p.get("state").equals("OPEN")&&before.get("state").equals("PENDING_CONFIRM");
            var after=open?transitionLocked(user,request,"confirm",true):before;boolean applied=open&&after.get("state").equals("CONFIRMED");String outcome=applied?"APPLIED":"REFUND_PENDING";
            db.update("INSERT INTO cx_receipt(channel_id,user_id,payment_id,amount_cents,outcome,created_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP(3))",channel,user,payment,amount,outcome);
            db.update("UPDATE cx_payment SET state=? WHERE user_id=? AND id=?",applied?"PAID":"REFUND_PENDING",user,payment);
            if(!applied)db.update("INSERT INTO cx_refund(channel_id,user_id,payment_id,amount_cents,next_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP(3))",channel,user,payment,amount);
            return db.queryForMap("SELECT * FROM cx_receipt WHERE user_id=? AND channel_id=?",user,channel);
        });
        fault.accept("core-receipt-before-ack");
        stock.db.update("UPDATE ux_core_channel_claim SET processed_at=CURRENT_TIMESTAMP(3) WHERE channel_id=? AND payment_id=? AND user_id=? AND processed_at IS NULL",channel,payment,user);
        return result;
    }
    public void apply(Map<String,Object> task){
        long user=number(task,"user_id");String request=task.get("request_id").toString(),kind=task.get("kind").toString();var r=request(user,request);
        if(kind.equals("INDEX")){var order=order(user,request);stock.db.update("INSERT IGNORE INTO ux_core_order_route(order_id,user_id,request_id,created_at) VALUES(?,?,?,CURRENT_TIMESTAMP(3))",order.get("id"),user,request);}
        else if(kind.startsWith("LOG_")){db.update("INSERT IGNORE INTO cx_check_log(id,order_id,user_id,event,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP(3))",id(),r.get("order_id"),user,kind,stock.now());}
        else {
            if(kind.equals("RESTORE")||kind.equals("RELEASE"))stock.release(user,request);
            redis.apply(number(r,"activity_id"),user,r.get("request_key").toString(),kind,(String)r.get("reservation_epoch"));
        }
        fault.accept("core-action-before-ack");db.update("UPDATE cx_action SET done_at=CURRENT_TIMESTAMP(3) WHERE user_id=? AND id=? AND done_at IS NULL",user,task.get("id"));
    }
    public void refund(Map<String,Object> task){
        long user=number(task,"user_id");String channel=task.get("channel_id").toString(),payment=task.get("payment_id").toString();
        stock.refund(user,payment,channel,((Number)task.get("amount_cents")).intValue());fault.accept("core-refund-before-ack");
        store.tx(user,()->{db.queryForList("SELECT id FROM cx_payment WHERE user_id=? AND id=? FOR UPDATE",user,payment);
            db.update("UPDATE cx_refund SET done_at=CURRENT_TIMESTAMP(3) WHERE user_id=? AND channel_id=? AND done_at IS NULL",user,channel);
            db.update("UPDATE cx_receipt SET outcome='REFUNDED' WHERE user_id=? AND channel_id=?",user,channel);
            if(db.queryForObject("SELECT COUNT(*) FROM cx_refund WHERE user_id=? AND payment_id=? AND done_at IS NULL",Integer.class,user,payment)==0)db.update("UPDATE cx_payment SET state='REFUNDED' WHERE user_id=? AND id=? AND state='REFUND_PENDING'",user,payment);return null;});
    }
    public void expireRequest(long user,String request){store.tx(user,()->{db.queryForList("SELECT id FROM cx_request WHERE user_id=? AND id=? FOR UPDATE",user,request);var r=request(user,request);if(r.get("state").equals("ACCEPTED")&&!stock.now().toInstant().isBefore(instant(r,"deadline"))){finish(user,request,"EXPIRED","PROCESS_DEADLINE");action(user,request,"RELEASE");}return null;});}
    public void orphan(long activity,String field,String epoch){
        int at=field.indexOf(':');if(at<=0)return;long user=Long.parseLong(field.substring(0,at));String key=field.substring(at+1);user(user);
        var r=store.tx(user,()->{lockUser(user);var existing=existing(user,key);if(existing!=null)return existing;
            String request=id();db.update("INSERT INTO cx_request(id,user_id,request_key,activity_id,order_id,state,reason,created_at,deadline,completed_at,reservation_epoch) VALUES(?,?,?,?,?,'EXPIRED','RESERVATION_ABANDONED',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),?)",request,user,key,activity,id(),epoch);action(user,request,"RELEASE");return request(user,request);});
        if(number(r,"activity_id")!=activity){redis.apply(activity,user,key,"RELEASE",epoch);return;}
        if(r.get("state").equals("ACCEPTED"))redis.postpone(activity,field,instant(r,"deadline").toEpochMilli());
    }
}
