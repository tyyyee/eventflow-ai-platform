package com.hmdp.upgrade.core32;

import com.hmdp.upgrade.Problem;
import java.util.*;

/** Maintenance only: quiesce writers/workers before rebuilding the Redis reservation index. */
public final class CoreRecovery {
    private final CoreEngine engine;
    public CoreRecovery(CoreEngine engine){this.engine=engine;}
    private void rebuild(long activity){
        if(!"PAUSED".equals(engine.stock.db.queryForObject("SELECT mode FROM ux_core_control WHERE id=1",String.class)))throw new Problem(409,"PAUSE_CORE_BEFORE_RECOVERY");
        var requests=new ArrayList<Map<String,Object>>();
        for(int shard=0;shard<32;shard++){
            String suffix="_"+shard%8;requests.addAll(engine.store.physical(shard).queryForList("SELECT r.*,o.state order_state FROM cx_request"+suffix+" r LEFT JOIN cx_order"+suffix+" o ON o.user_id=r.user_id AND o.request_id=r.id WHERE r.activity_id=? LIMIT 10001",activity));
            if(requests.size()>10000)throw new Problem(409,"RECOVERY_BATCH_REQUIRED");
        }
        for(var r:requests)if(List.of("REJECTED","EXPIRED").contains(r.get("state"))||List.of("CANCELLED","EXPIRED").contains(r.get("order_state")==null?"":r.get("order_state")))engine.stock.release(CoreEngine.number(r,"user_id"),r.get("id").toString());
        var held=new HashSet<>(engine.stock.db.queryForList("SELECT request_id FROM ux_core_stock_hold WHERE activity_id=? AND state='HELD'",String.class,activity));
        long pending=requests.stream().filter(r->r.get("state").equals("ACCEPTED")&&!held.contains(r.get("id").toString())).count();
        int available=((Number)engine.stock.activity(activity).get("available")).intValue();if(pending>available)throw new Problem(409,"DRAIN_ACCEPTED_BEFORE_RECOVERY");
        String epoch=UUID.randomUUID().toString();engine.redis.rebuild(activity,available-(int)pending,epoch,requests);
        engine.stock.db.update("INSERT INTO ux_core_reservation_epoch(activity_id,epoch) VALUES(?,?) ON DUPLICATE KEY UPDATE epoch=VALUES(epoch),changed_at=CURRENT_TIMESTAMP(3)",activity,epoch);
        for(var r:requests)engine.db.update("UPDATE cx_request SET reservation_epoch=? WHERE user_id=? AND id=?",epoch,r.get("user_id"),r.get("id"));
    }
    private void mark(String key,boolean done){engine.stock.db.update("INSERT INTO ux_core_migration(name,done) VALUES(?,?) ON DUPLICATE KEY UPDATE done=VALUES(done),changed_at=CURRENT_TIMESTAMP(3)",key,done);}
    public void activity(long activity){String key="recovery_"+activity;mark(key,false);rebuild(activity);mark(key,true);}
    public int all(){mark("recovery_all",false);int total=0;for(long activity:engine.stock.db.queryForList("SELECT id FROM ux_activity ORDER BY id",Long.class)){rebuild(activity);total++;}mark("recovery_all",true);return total;}
}
