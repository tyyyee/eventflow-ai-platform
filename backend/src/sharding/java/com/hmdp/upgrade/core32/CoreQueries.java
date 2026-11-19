package com.hmdp.upgrade.core32;

import com.hmdp.upgrade.*;
import java.util.*;
import java.sql.*;
import java.time.Instant;

/** Binding joins are user-to-user; event configuration is a read-only broadcast snapshot. */
public final class CoreQueries {
    private final CoreEngine engine;
    public CoreQueries(CoreEngine engine){this.engine=engine;}
    private static final String SELECT="""
        SELECT r.*,o.id order_id,o.state order_state,o.confirm_until,
        COALESCE(o.price_cents,e.price_cents) price_cents,e.shop_id,e.shop_name,e.offer_title,e.image_path
        FROM cx_request r LEFT JOIN cx_order o ON o.user_id=r.user_id AND o.request_id=r.id
        LEFT JOIN cx_event e ON e.activity_id=r.activity_id
        """;
    private static CustomerRequests.Item item(ResultSet r,int n)throws SQLException{
        Timestamp until=r.getTimestamp("confirm_until");
        return new CustomerRequests.Item(r.getString("id"),r.getString("request_key"),r.getLong("activity_id"),r.getString("state"),r.getString("reason"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("deadline").toInstant(),r.getString("offer_title"),r.getObject("shop_id")==null?null:r.getLong("shop_id"),r.getString("shop_name"),r.getString("image_path"),r.getInt("price_cents"),r.getString("order_id"),r.getString("order_state"),until==null?null:until.toInstant());
    }
    public CustomerRequests.Listing list(long user,int page,String state){
        if(page<1||page>500||!List.of("","pending","confirmed","closed").contains(state))throw new Problem(400,"INVALID_FILTER");
        String filter=switch(state){case "pending"->" AND (r.state='ACCEPTED' OR o.state='PENDING_CONFIRM')";case "confirmed"->" AND o.state='CONFIRMED'";case "closed"->" AND (r.state IN ('REJECTED','EXPIRED') OR o.state IN ('CANCELLED','EXPIRED'))";default->"";};
        var items=engine.db.query(SELECT+" WHERE r.user_id=?"+filter+" ORDER BY r.created_at DESC,r.id DESC LIMIT 20 OFFSET ?",CoreQueries::item,user,(page-1)*20);
        String join=" FROM cx_request r LEFT JOIN cx_order o ON o.user_id=r.user_id AND o.request_id=r.id WHERE r.user_id=?";
        int total=engine.db.queryForObject("SELECT COUNT(*)"+join+filter,Integer.class,user),pending=engine.db.queryForObject("SELECT COUNT(*)"+join+" AND (r.state='ACCEPTED' OR o.state='PENDING_CONFIRM')",Integer.class,user);
        return new CustomerRequests.Listing(items,total,pending,page,engine.stock.now().toInstant());
    }
    public CustomerRequests.Detail detail(long user,String value,boolean key){var items=engine.db.query(SELECT+" WHERE r.user_id=? AND r."+(key?"request_key":"id")+"=?",CoreQueries::item,user,value);if(items.isEmpty())throw new Problem(404,"REQUEST_NOT_FOUND");return new CustomerRequests.Detail(items.get(0),engine.stock.now().toInstant());}
    public Map<String,Object> history(long user,int page){
        if(page<1||page>500)throw new Problem(400,"INVALID_HISTORY_QUERY");
        var rows=engine.db.query("SELECT o.*,e.shop_id,e.shop_name,e.offer_title,e.image_path FROM cx_order o LEFT JOIN cx_event e ON e.activity_id=o.activity_id WHERE o.user_id=? ORDER BY o.created_at DESC,o.id DESC LIMIT 20 OFFSET ?",(r,n)->new OrderProjection.HistoryItem(r.getString("id"),r.getString("request_id"),r.getLong("activity_id"),r.getInt("price_cents"),r.getString("state"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("confirm_until").toInstant(),r.getObject("shop_id")==null?null:r.getLong("shop_id"),r.getString("shop_name"),r.getString("offer_title"),r.getString("image_path")),user,(page-1)*20);
        int total=engine.db.queryForObject("SELECT COUNT(*) FROM cx_order WHERE user_id=?",Integer.class,user);return Map.of("items",rows,"total",total,"page",page,"consistency","primary");
    }
    public Map<String,Object> byOrder(String order){var routes=engine.stock.db.queryForList("SELECT user_id FROM ux_core_order_route WHERE order_id=?",order);if(routes.isEmpty())throw new Problem(404,"ORDER_ROUTE_NOT_READY_OR_MISSING");long user=CoreEngine.number(routes.get(0),"user_id");return engine.db.queryForMap("SELECT * FROM cx_order WHERE user_id=? AND id=?",user,order);}
    public Map<String,Object> status(){
        long accepted=0,outbox=0,actions=0,overdue=0,refunds=0,stateViolations=0;var topology=engine.store.topology();
        for(int shard=0;shard<32;shard++){
            var db=engine.store.physical(shard);String suffix="_"+shard%8;
            accepted+=db.queryForObject("SELECT COUNT(*) FROM cx_request"+suffix+" WHERE state='ACCEPTED'",Long.class);
            outbox+=db.queryForObject("SELECT COUNT(*) FROM cx_outbox"+suffix+" WHERE sent_at IS NULL",Long.class);
            actions+=db.queryForObject("SELECT COUNT(*) FROM cx_action"+suffix+" WHERE done_at IS NULL",Long.class);
            overdue+=db.queryForObject("SELECT COUNT(*) FROM cx_close"+suffix+" WHERE done_at IS NULL AND due_at<=CURRENT_TIMESTAMP(3)",Long.class);
            refunds+=db.queryForObject("SELECT COUNT(*) FROM cx_refund"+suffix+" WHERE done_at IS NULL",Long.class);
            stateViolations+=db.queryForObject("SELECT COUNT(*) FROM cx_request"+suffix+" r WHERE (r.state='SUCCEEDED' AND NOT EXISTS(SELECT 1 FROM cx_order"+suffix+" o WHERE o.request_id=r.id AND o.user_id=r.user_id)) OR (r.state<>'SUCCEEDED' AND EXISTS(SELECT 1 FROM cx_order"+suffix+" o WHERE o.request_id=r.id AND o.user_id=r.user_id))",Long.class);
        }
        return Map.of("mode",engine.stock.db.queryForObject("SELECT mode FROM ux_core_control WHERE id=1",String.class),"topology",topology,"invariants",Map.of("stockViolations",engine.stock.invariants().get("stockViolations"),"stateViolations",stateViolations),"queues",Map.of("accepted",accepted,"outbox",outbox,"actions",actions,"overdue",overdue,"refunds",refunds));
    }
}
