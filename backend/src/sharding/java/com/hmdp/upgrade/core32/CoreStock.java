package com.hmdp.upgrade.core32;

import com.hmdp.upgrade.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.sql.Timestamp;

/** A stock grant is a durable idempotency record, not an expiring Redis lock. */
public final class CoreStock {
    public final JdbcTemplate db;private final Transactions tx;
    public CoreStock(JdbcTemplate db,Transactions tx){this.db=db;this.tx=tx;}
    public Map<String,Object> activity(long activity){var rows=db.queryForList("SELECT * FROM ux_activity WHERE id=?",activity);if(rows.isEmpty())throw new Problem(404,"ACTIVITY_NOT_FOUND");return rows.get(0);}
    public Timestamp now(){return db.queryForObject("SELECT CURRENT_TIMESTAMP(3)",Timestamp.class);}
    public int hold(long user,String request,long activity){return tx.run(()->{
        var a=db.queryForList("SELECT * FROM ux_activity WHERE id=? FOR UPDATE",activity);if(a.isEmpty())throw new Problem(404,"ACTIVITY_NOT_FOUND");
        var existing=db.queryForList("SELECT * FROM ux_core_stock_hold WHERE request_id=?",request);
        if(!existing.isEmpty()){var row=existing.get(0);if(((Number)row.get("user_id")).longValue()!=user||((Number)row.get("activity_id")).longValue()!=activity)throw new Problem(409,"STOCK_GRANT_MISMATCH");if(!row.get("state").equals("HELD"))throw new Problem(409,"STOCK_GRANT_RELEASED");return ((Number)row.get("price_cents")).intValue();}
        if(db.queryForObject("SELECT COUNT(*) FROM ux_core_stock_hold WHERE activity_id=? AND user_id=?",Integer.class,activity,user)>0)throw new Problem(409,"ALREADY_PURCHASED");
        if(db.update("UPDATE ux_activity SET available=available-1 WHERE id=? AND available>0",activity)!=1)throw new Problem(409,"SOLD_OUT");
        int price=((Number)a.get(0).get("price_cents")).intValue();
        db.update("INSERT INTO ux_core_stock_hold(request_id,user_id,activity_id,price_cents,state,created_at) VALUES(?,?,?,?,'HELD',CURRENT_TIMESTAMP(3))",request,user,activity,price);
        return price;
    });}
    public void release(long user,String request){tx.run(()->{
        var initial=db.queryForList("SELECT activity_id FROM ux_core_stock_hold WHERE request_id=? AND user_id=?",request,user);if(initial.isEmpty())return null;
        long activity=((Number)initial.get(0).get("activity_id")).longValue();db.queryForList("SELECT id FROM ux_activity WHERE id=? FOR UPDATE",activity);
        if(db.update("UPDATE ux_core_stock_hold SET state='RELEASED',released_at=CURRENT_TIMESTAMP(3) WHERE request_id=? AND user_id=? AND state='HELD'",request,user)==1)
            if(db.update("UPDATE ux_activity SET available=available+1 WHERE id=? AND available<capacity",activity)!=1)throw new IllegalStateException("Stock restoration violated capacity");return null;
    });}
    public void channel(long user,String payment,String channel,int amount){
        if(channel==null||!channel.matches("[A-Za-z0-9_-]{8,64}"))throw new Problem(400,"INVALID_CHANNEL_ID");
        tx.run(()->{db.update("INSERT IGNORE INTO ux_core_channel_claim(channel_id,payment_id,user_id,amount_cents,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP(3))",channel,payment,user,amount);
            var row=db.queryForMap("SELECT * FROM ux_core_channel_claim WHERE channel_id=?",channel);
            if(!row.get("payment_id").equals(payment)||((Number)row.get("user_id")).longValue()!=user||((Number)row.get("amount_cents")).intValue()!=amount)throw new Problem(409,"PAYMENT_RECEIPT_CONFLICT");return null;});
    }
    public void refund(long user,String payment,String channel,int amount){tx.run(()->{
        var row=db.queryForMap("SELECT * FROM ux_core_channel_claim WHERE channel_id=?",channel);
        if(!row.get("payment_id").equals(payment)||((Number)row.get("user_id")).longValue()!=user||((Number)row.get("amount_cents")).intValue()!=amount)throw new Problem(409,"REFUND_MISMATCH");
        db.update("INSERT IGNORE INTO ux_core_provider_refund(channel_id,amount_cents,created_at) VALUES(?,?,CURRENT_TIMESTAMP(3))",channel,amount);return null;
    });}
    public Map<String,Object> invariants(){return db.queryForMap("SELECT COUNT(*) stockViolations FROM ux_activity a WHERE a.available<0 OR a.available>a.capacity OR a.available+(SELECT COUNT(*) FROM ux_core_stock_hold h WHERE h.activity_id=a.id AND h.state='HELD')<>a.capacity");}
}
