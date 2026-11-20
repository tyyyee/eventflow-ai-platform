package com.hmdp.upgrade;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

public final class HistoryRows {
    private HistoryRows() {}
    public static String table(long user) {
        if(user<=0) throw new Problem(400,"INVALID_USER");
        return "ux_order_read_"+String.format(java.util.Locale.ROOT,"%02d",Math.floorMod(user,32));
    }
    public static void apply(JdbcTemplate db,String table,Map<String,Object> r) {
        if(!table.matches("ux_order_history|ux_order_read_[0-9]{2}")) throw new IllegalArgumentException("Invalid history table");
        // The last assignment updates revision, after all comparisons against the old value.
        db.update("INSERT INTO "+table+"(id,user_id,request_id,activity_id,price_cents,state,created_at,confirm_until,revision,shop_id,shop_name,offer_title,image_path) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) "
            +"ON DUPLICATE KEY UPDATE state=CASE WHEN revision<VALUES(revision) THEN VALUES(state) ELSE state END,confirm_until=CASE WHEN revision<VALUES(revision) THEN VALUES(confirm_until) ELSE confirm_until END,"
            +"request_id=CASE WHEN revision<VALUES(revision) THEN VALUES(request_id) ELSE request_id END,activity_id=CASE WHEN revision<VALUES(revision) THEN VALUES(activity_id) ELSE activity_id END,created_at=CASE WHEN revision<VALUES(revision) THEN VALUES(created_at) ELSE created_at END,"
            +"price_cents=CASE WHEN revision<VALUES(revision) THEN VALUES(price_cents) ELSE price_cents END,"
            +"shop_id=CASE WHEN revision<VALUES(revision) THEN VALUES(shop_id) ELSE shop_id END,shop_name=CASE WHEN revision<VALUES(revision) THEN VALUES(shop_name) ELSE shop_name END,"
            +"offer_title=CASE WHEN revision<VALUES(revision) THEN VALUES(offer_title) ELSE offer_title END,image_path=CASE WHEN revision<VALUES(revision) THEN VALUES(image_path) ELSE image_path END,revision=GREATEST(revision,VALUES(revision))",
            r.get("id"),r.get("user_id"),r.get("request_id"),r.get("activity_id"),r.get("price_cents"),r.get("state"),r.get("created_at"),r.get("confirm_until"),r.get("revision"),
            r.get("shop_id"),r.get("shop_name"),r.get("offer_title"),r.get("image_path"));
    }
    static final String COLUMNS="id,request_id,activity_id,price_cents,state,created_at,confirm_until,shop_id,shop_name,offer_title,image_path";
    public static OrderProjection.HistoryItem item(ResultSet r,int ignored) throws SQLException {
        return new OrderProjection.HistoryItem(r.getString("id"),r.getString("request_id"),r.getLong("activity_id"),r.getInt("price_cents"),r.getString("state"),
            r.getTimestamp("created_at").toInstant(),r.getTimestamp("confirm_until").toInstant(),r.getObject("shop_id")==null?null:r.getLong("shop_id"),
            r.getString("shop_name"),r.getString("offer_title"),r.getString("image_path"));
    }
}
