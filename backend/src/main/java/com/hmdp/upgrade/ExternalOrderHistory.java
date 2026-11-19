package com.hmdp.upgrade;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/** Separate at-least-once projection; never encloses a remote write in a source transaction. */
@Service
public class ExternalOrderHistory {
    private final JdbcTemplate source;
    private final Transactions tx;
    private final HistoryReplica replica;
    private final LongAdder failures=new LongAdder();
    @Value("${upgrade.jobs:true}") private boolean jobs=true;
    @Value("${upgrade.history.replica-enabled:false}") private boolean configured;
    @Value("${upgrade.history.sync-batch:100}") private int syncBatch=100;
    @jakarta.annotation.PostConstruct void validateConfiguration() {
        if(configured && replica==null) throw new IllegalStateException("History replica requested; build with Maven profile sharding and configure both query databases.");
        limit(syncBatch);
    }
    static final String SNAPSHOT="""
        SELECT o.*,p.revision,v.shop_id,v.title AS offer_title,s.name AS shop_name,f.image_path
        FROM ux_order o JOIN ux_order_projection p ON p.order_id=o.id
        LEFT JOIN ux_offer v ON v.activity_id=o.activity_id
        LEFT JOIN ux_shop s ON s.id=v.shop_id LEFT JOIN ux_storefront f ON f.shop_id=s.id
        """;
    @org.springframework.beans.factory.annotation.Autowired
    public ExternalOrderHistory(JdbcTemplate source,Transactions tx,ObjectProvider<HistoryReplica> replicas) {
        this(source,tx,replicas.getIfAvailable());
    }
    public ExternalOrderHistory(JdbcTemplate source,Transactions tx,HistoryReplica replica) {
        this.source=source;this.tx=tx;this.replica=replica;
    }
    public boolean enabled() { return replica!=null; }
    private void requireEnabled() { if(!enabled()) throw new Problem(503,"HISTORY_REPLICA_DISABLED"); }
    @Scheduled(fixedDelayString="${upgrade.history.sync-delay-ms:1000}")
    public void tick() {
        if(!enabled() || !jobs) return;
        try { drain(syncBatch); } catch(RuntimeException failure) { failures.increment(); }
    }
    public int drain(int limit) {
        requireEnabled();limit(limit);
        var rows=source.queryForList(SNAPSHOT+" WHERE p.replica_pending=1 AND p.replica_next_at<=CURRENT_TIMESTAMP(3) ORDER BY p.replica_next_at,p.changed_at,p.order_id LIMIT ?",limit);
        int done=0;
        for(var row:rows) {
            try {
                replica.apply(row);
                // A new source revision cannot be accidentally marked as already applied.
                done+=source.update("UPDATE ux_order_projection SET replica_revision=?,replica_attempts=0,replica_error=NULL WHERE order_id=? AND revision=? AND replica_revision<?",
                    row.get("revision"),row.get("id"),row.get("revision"),row.get("revision"));
            } catch(RuntimeException failure) {
                failures.increment();
                source.update("UPDATE ux_order_projection SET replica_next_at=TIMESTAMPADD(SECOND,LEAST(60,POW(2,LEAST(replica_attempts,5))),CURRENT_TIMESTAMP(3)),replica_attempts=replica_attempts+1,replica_error='TARGET_WRITE_FAILED' WHERE order_id=? AND revision=?",
                    row.get("id"),row.get("revision"));
                // One unavailable target must not spend a full batch on connection timeouts.
                break;
            }
        }
        return done;
    }
    private static void limit(int limit) { if(limit<1 || limit>100) throw new Problem(400,"INVALID_BATCH"); }
    private Map<String,Object> control() {return source.queryForMap("SELECT * FROM ux_history_control WHERE id=1");}
    private record Claim(String owner,String cursor) {}
    private Claim claim(boolean verify) {
        return tx.run(()->{
            var c=source.queryForMap("SELECT * FROM ux_history_control WHERE id=1 FOR UPDATE");
            if(verify && !Boolean.TRUE.equals(c.get("backfill_done"))) throw new Problem(409,"BACKFILL_REQUIRED");
            if(!verify && Boolean.TRUE.equals(c.get("backfill_done"))) return null;
            var now=source.queryForObject("SELECT CURRENT_TIMESTAMP(3)",Timestamp.class);
            if(c.get("lease_until")!=null && ((Timestamp)c.get("lease_until")).after(now)) throw new Problem(409,"HISTORY_MAINTENANCE_BUSY");
            String owner=UUID.randomUUID().toString();
            source.update("UPDATE ux_history_control SET lease_owner=?,lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3)) WHERE id=1",owner);
            return new Claim(owner,c.get(verify?"verify_cursor":"backfill_cursor").toString());
        });
    }
    public Map<String,Object> backfill(int limit) {
        requireEnabled();limit(limit);
        Claim claim=claim(false);if(claim==null) return Map.of("done",true,"count",0);
        try {
            var ids=source.queryForList("SELECT id FROM ux_order WHERE id>? ORDER BY id LIMIT ?",String.class,claim.cursor(),limit);
            for(String id:ids) {
                source.update("INSERT INTO ux_order_projection(order_id,revision,applied_revision,changed_at) VALUES(?,1,0,CURRENT_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE order_id=order_id",id);
                replica.apply(source.queryForMap(SNAPSHOT+" WHERE o.id=?",id));
            }
            String cursor=ids.isEmpty()?claim.cursor():ids.get(ids.size()-1);
            int changed=source.update("UPDATE ux_history_control SET backfill_cursor=?,backfill_done=?,lease_owner=NULL,lease_until=NULL,verified_at=NULL WHERE id=1 AND lease_owner=? AND lease_until>CURRENT_TIMESTAMP(3)",
                cursor,ids.isEmpty(),claim.owner());
            return Map.of("done",ids.isEmpty() && changed==1,"count",ids.size(),"cursor",cursor,"checkpointSaved",changed==1);
        } finally {
            source.update("UPDATE ux_history_control SET lease_owner=NULL,lease_until=NULL WHERE id=1 AND lease_owner=?",claim.owner());
        }
    }
    public Map<String,Object> verify(int limit) {
        requireEnabled();limit(limit);
        Claim claim=claim(true);
        try {
            var rows=source.queryForList(SNAPSHOT+" WHERE o.id>? ORDER BY o.id LIMIT ?",claim.cursor(),limit);
            int differences=0;
            for(var row:rows) {
                var target=replica.jdbc().queryForList("SELECT * FROM ux_order_history WHERE user_id=? AND id=?",row.get("user_id"),row.get("id"));
                if(target.isEmpty() || !same(row,target.get(0))) {
                    differences++;
                    source.update("UPDATE ux_order_projection SET revision=revision+1,replica_revision=0,replica_next_at=CURRENT_TIMESTAMP(3) WHERE order_id=?",row.get("id"));
                }
            }
            if(!rows.isEmpty()) {
                source.update("UPDATE ux_history_control SET verify_cursor=?,verify_checked=verify_checked+?,verify_mismatches=verify_mismatches+?,verified_at=NULL WHERE id=1 AND lease_owner=?",
                    rows.get(rows.size()-1).get("id"),rows.size(),differences,claim.owner());
                return Map.of("done",false,"checked",rows.size(),"differences",differences);
            }
            var c=control();
            var topology=replica.audit();
            boolean pass=((Number)c.get("verify_mismatches")).longValue()==0 && pending()==0
                && topology.get("misplaced")==0 && topology.get("rows").equals(source.queryForObject("SELECT COUNT(*) FROM ux_order",Long.class));
            long checked=((Number)c.get("verify_checked")).longValue(),mismatches=((Number)c.get("verify_mismatches")).longValue();
            source.update("UPDATE ux_history_control SET verify_cursor='',verify_checked=0,verify_mismatches=0,verified_at=? WHERE id=1 AND lease_owner=?",
                pass?source.queryForObject("SELECT CURRENT_TIMESTAMP(3)",Timestamp.class):null,claim.owner());
            return Map.of("done",true,"passed",pass,"checked",checked,"differences",mismatches,"topology",topology);
        } finally {
            source.update("UPDATE ux_history_control SET lease_owner=NULL,lease_until=NULL WHERE id=1 AND lease_owner=?",claim.owner());
        }
    }
    static boolean same(Map<String,Object> a,Map<String,Object> b) {
        for(String field:List.of("id","user_id","request_id","activity_id","price_cents","state","created_at","confirm_until","revision","shop_id","shop_name","offer_title","image_path"))
            if(!Objects.equals(a.get(field),b.get(field))) return false;
        return true;
    }
    public long pending() {return source.queryForObject("SELECT COUNT(*) FROM ux_order_projection WHERE replica_pending=1",Long.class);}
    public Map<String,Object> status() {
        if(!enabled()) return Map.of("enabled",false,"readMode","LOCAL");
        var result=new LinkedHashMap<String,Object>(control());
        result.remove("lease_owner");result.put("enabled",true);result.put("pending",pending());result.put("failures",failures.sum());
        result.put("oldestPendingAt",source.queryForObject("SELECT MIN(changed_at) FROM ux_order_projection WHERE replica_pending=1",Timestamp.class));
        result.put("physicalQueryDatabases",replica.physicalDatabases());result.put("tables",32);return result;
    }
    public void readMode(String mode,long actor) {
        if(mode==null || !Set.of("LOCAL","SHARDED").contains(mode)) throw new Problem(400,"INVALID_HISTORY_MODE");
        if(mode.equals("SHARDED")) requireEnabled();
        tx.run(()->{
            var c=source.queryForMap("SELECT * FROM ux_history_control WHERE id=1 FOR UPDATE");
            if(mode.equals("SHARDED") && (!Boolean.TRUE.equals(c.get("backfill_done")) || c.get("verified_at")==null || pending()!=0))
                throw new Problem(409,"HISTORY_NOT_VERIFIED");
            source.update("UPDATE ux_history_control SET read_mode=? WHERE id=1",mode);
            source.update("INSERT INTO ux_audit(id,actor,action,target,created_at) VALUES(?,?,'HISTORY_READ_MODE',?,CURRENT_TIMESTAMP(3))",UUID.randomUUID().toString(),actor,mode);
            return null;
        });
    }
    public boolean readsShards() {return enabled() && "SHARDED".equals(control().get("read_mode"));}
    public List<OrderProjection.HistoryItem> list(long user,int page) {
        requireEnabled();validate(user,page);
        try {return replica.jdbc().query("SELECT "+HistoryRows.COLUMNS+" FROM ux_order_history WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 20 OFFSET ?",HistoryRows::item,user,(page-1)*20);}
        catch(org.springframework.dao.DataAccessException unavailable) {throw new Problem(503,"HISTORY_QUERY_UNAVAILABLE");}
    }
    public int count(long user) {
        requireEnabled();validate(user,1);
        try {return replica.jdbc().queryForObject("SELECT COUNT(*) FROM ux_order_history WHERE user_id=?",Integer.class,user);}
        catch(org.springframework.dao.DataAccessException unavailable) {throw new Problem(503,"HISTORY_QUERY_UNAVAILABLE");}
    }
    private static void validate(long user,int page) {if(user<=0 || page<1 || page>500) throw new Problem(400,"INVALID_HISTORY_QUERY");}
}
