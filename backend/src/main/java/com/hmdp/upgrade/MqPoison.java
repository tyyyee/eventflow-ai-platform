package com.hmdp.upgrade;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Broker + queue + offset identifies a physical delivery even with multiple brokers. */
public final class MqPoison {
    private MqPoison(){}
    public static void persist(JdbcTemplate db,OrderMessages.Received message,String reason){
        String identity=message.topic()+"|"+message.broker()+"|"+message.queue()+"|"+message.offset();
        String id=UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        String body=message.body()==null?"null":message.body().substring(0,Math.min(message.body().length(),8192));
        db.update("INSERT INTO ux_mq_poison(id,topic,broker,queue_id,offset_id,body,reason,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE id=id",
            id,message.topic(),message.broker()==null?"unknown":message.broker(),message.queue(),message.offset(),body,reason);
    }
}
