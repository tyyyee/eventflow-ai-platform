package com.hmdp.upgrade.core32;

import com.hmdp.upgrade.*;
import java.util.*;
import java.sql.Timestamp;
import org.springframework.scheduling.annotation.Scheduled;

/** The activity-wide candidate queue stays central; promotion creates a user-sharded request. */
public final class CoreWaitlists {
    private final CoreEngine engine;private final Transactions tx;private final boolean jobs;
    public CoreWaitlists(CoreEngine engine,Transactions tx,boolean jobs){this.engine=engine;this.tx=tx;this.jobs=jobs;}
    public Waitlists.Entry join(long user,long activity){
        engine.user(user);var a=engine.stock.activity(activity);var now=engine.stock.now();
        if(now.before((Timestamp)a.get("starts_at"))||!now.before((Timestamp)a.get("ends_at")))throw new Problem(409,"ACTIVITY_CLOSED");
        if(engine.db.queryForObject("SELECT COUNT(*) FROM cx_request WHERE user_id=? AND activity_id=?",Integer.class,user,activity)>0)throw new Problem(409,"ALREADY_PURCHASED");
        if(engine.redis.remaining(activity)>0||((Number)a.get("available")).intValue()>0)throw new Problem(409,"STILL_AVAILABLE");
        String id=engine.id(),key="wait_"+id;
        tx.run(()->{engine.stock.db.queryForList("SELECT id FROM ux_account WHERE id=? FOR UPDATE",user);
            var previous=engine.stock.db.queryForList("SELECT * FROM ux_waitlist WHERE user_id=? AND activity_id=?",user,activity);
            if(previous.isEmpty())engine.stock.db.update("INSERT INTO ux_waitlist(id,activity_id,user_id,state,request_key,created_at,updated_at) VALUES(?,?,?,'WAITING',?,?,?)",id,activity,user,key,now,now);
            else if(List.of("CANCELLED","EXPIRED").contains(previous.get(0).get("state")))engine.stock.db.update("UPDATE ux_waitlist SET state='WAITING',request_key=?,created_at=?,updated_at=?,promoted_request_id=NULL WHERE id=?",key,now,now,previous.get(0).get("id"));return null;});
        return list(user).items().stream().filter(x->x.activityId()==activity).findFirst().orElseThrow();
    }
    public Waitlists.Listing list(long user){
        var rows=engine.stock.db.queryForList("SELECT w.*,v.title offer_title,s.name shop_name,f.image_path,a.price_cents,CASE WHEN w.state='WAITING' THEN (SELECT COUNT(*) FROM ux_waitlist q WHERE q.activity_id=w.activity_id AND q.state='WAITING' AND (q.created_at<w.created_at OR(q.created_at=w.created_at AND q.id<=w.id))) ELSE 0 END position FROM ux_waitlist w JOIN ux_activity a ON a.id=w.activity_id LEFT JOIN ux_offer v ON v.activity_id=w.activity_id LEFT JOIN ux_shop s ON s.id=v.shop_id LEFT JOIN ux_storefront f ON f.shop_id=s.id WHERE w.user_id=? ORDER BY w.created_at DESC LIMIT 100",user);
        var items=new ArrayList<Waitlists.Entry>();for(var r:rows){String request=null;if(r.get("state").equals("PROMOTED")){var accepted=engine.existing(user,r.get("request_key").toString());if(accepted!=null)request=accepted.get("id").toString();}
            items.add(new Waitlists.Entry(r.get("id").toString(),CoreEngine.number(r,"activity_id"),r.get("state").toString(),((Number)r.get("position")).intValue(),CoreEngine.instant(r,"created_at"),request,(String)r.get("offer_title"),(String)r.get("shop_name"),(String)r.get("image_path"),((Number)r.get("price_cents")).intValue()));}
        return new Waitlists.Listing(items,engine.stock.now().toInstant());
    }
    public Waitlists.Entry cancel(long user,String id){engine.stock.db.update("UPDATE ux_waitlist SET state='CANCELLED',updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND user_id=? AND state='WAITING'",id,user);var current=list(user).items().stream().filter(x->x.id().equals(id)).findFirst().orElseThrow(()->new Problem(404,"WAITLIST_NOT_FOUND"));if(!current.state().equals("CANCELLED"))throw new Problem(409,"WAITLIST_TERMINAL");return current;}
    @Scheduled(fixedDelay=1000)public void tick(){
        if(!jobs||!"CORE".equals(engine.stock.db.queryForObject("SELECT mode FROM ux_core_control WHERE id=1",String.class)))return;
        engine.stock.db.update("UPDATE ux_waitlist SET state='WAITING',updated_at=CURRENT_TIMESTAMP(3) WHERE state='PROMOTING' AND updated_at<TIMESTAMPADD(SECOND,-30,CURRENT_TIMESTAMP(3))");
        for(int i=0;i<8;i++){
            var row=tx.run(()->{var rows=engine.stock.db.queryForList("SELECT w.* FROM ux_waitlist w JOIN ux_activity a ON a.id=w.activity_id WHERE w.state='WAITING' AND a.available>0 AND a.ends_at>CURRENT_TIMESTAMP(3) ORDER BY w.created_at,w.id LIMIT 1 FOR UPDATE SKIP LOCKED");if(rows.isEmpty())return null;var r=rows.get(0);engine.stock.db.update("UPDATE ux_waitlist SET state='PROMOTING',updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",r.get("id"));return r;});if(row==null)break;
            long user=CoreEngine.number(row,"user_id");String state="WAITING";
            try{var accepted=engine.purchase(user,row.get("request_key").toString(),CoreEngine.number(row,"activity_id"));state=List.of("ACCEPTED","SUCCEEDED").contains(accepted.state())?"PROMOTED":"EXPIRED";}
            catch(Problem p){if(List.of("ALREADY_PURCHASED","ACTIVITY_CLOSED").contains(p.code))state="EXPIRED";}catch(RuntimeException unavailable){/* durable queue retries; unknown accepts replay the same request key */}
            engine.stock.db.update("UPDATE ux_waitlist SET state=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND state='PROMOTING'",state,row.get("id"));
        }
    }
}
