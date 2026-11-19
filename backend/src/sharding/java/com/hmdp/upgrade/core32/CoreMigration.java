package com.hmdp.upgrade.core32;

import com.hmdp.upgrade.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.*;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;

/** Snapshot + transactional source journal; forward copying never runs after primary-shard activation. */
public final class CoreMigration {
    private final CoreEngine engine;private final JdbcTemplate source;private final TransactionTemplate snapshot,sourceTx;
    public CoreMigration(CoreEngine engine){this.engine=engine;source=engine.stock.db;var manager=new DataSourceTransactionManager(source.getDataSource());snapshot=new TransactionTemplate(manager);snapshot.setIsolationLevel(4);snapshot.setReadOnly(true);snapshot.setTimeout(15);sourceTx=new TransactionTemplate(manager);sourceTx.setTimeout(15);}
    static final Map<String,List<String>> COLUMNS=new LinkedHashMap<>();
    static{
        COLUMNS.put("cx_user",List.of("user_id","username","display_name","created_at"));
        COLUMNS.put("cx_request",List.of("id","user_id","request_key","activity_id","order_id","state","reason","created_at","deadline","completed_at"));
        COLUMNS.put("cx_order",List.of("id","user_id","request_id","activity_id","price_cents","state","confirm_until","created_at"));
        COLUMNS.put("cx_outbox",List.of("id","user_id","request_id","activity_id","created_at","sent_at","next_at"));
        COLUMNS.put("cx_payment",List.of("id","user_id","request_id","amount_cents","state","created_at"));
        COLUMNS.put("cx_receipt",List.of("channel_id","user_id","payment_id","amount_cents","outcome","created_at"));
        COLUMNS.put("cx_refund",List.of("channel_id","user_id","payment_id","amount_cents","attempts","next_at","done_at"));
        COLUMNS.put("cx_close",List.of("request_id","user_id","due_at","done_at"));
    }
    private String mode(){return source.queryForObject("SELECT mode FROM ux_core_control WHERE id=1",String.class);}
    public void begin(){String mode=mode();if(!List.of("LEGACY","MIGRATING").contains(mode))throw new Problem(409,"CORE_ALREADY_PRIMARY");source.update("UPDATE ux_core_control SET mode='MIGRATING',changed_at=CURRENT_TIMESTAMP(3) WHERE id=1");source.update("INSERT INTO ux_core_copy_user(user_id) SELECT id FROM ux_account ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)");}
    @Scheduled(fixedDelay=1000)public void tick(){if("MIGRATING".equals(mode()))try{drain(4);}catch(RuntimeException ignored){/* intent, lease and error remain in the catalog */}}
    public long pending(){return source.queryForObject("SELECT COUNT(*) FROM ux_core_copy_user WHERE applied_revision<revision",Long.class);}
    public int drain(int limit){
        if(!List.of("MIGRATING","PAUSED").contains(mode()))throw new Problem(409,"MIGRATION_MODE_REQUIRED");if(limit<1||limit>100)throw new Problem(400,"INVALID_BATCH");int done=0;
        for(var row:source.queryForList("SELECT * FROM ux_core_copy_user WHERE applied_revision<revision AND next_at<=CURRENT_TIMESTAMP(3) AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(3)) ORDER BY changed_at,user_id LIMIT ?",limit)){
            long user=CoreEngine.number(row,"user_id"),revision=CoreEngine.number(row,"revision");String owner=UUID.randomUUID().toString();
            if(source.update("UPDATE ux_core_copy_user SET owner=?,lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3)) WHERE user_id=? AND revision=? AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(3))",owner,user,revision)!=1)continue;
            try{
                var data=read(user);engine.store.tx(user,()->{
                    var profiles=engine.db.queryForList("SELECT * FROM cx_user WHERE user_id=? FOR UPDATE",user);
                    if(!profiles.isEmpty()&&CoreEngine.number(profiles.get(0),"copy_revision")>=revision)return null;
                    data.forEach((table,rows)->rows.forEach(r->upsert(engine.db,table,COLUMNS.get(table),r)));
                    engine.db.update("UPDATE cx_close SET message_sent_at=NULL,next_enqueue_at=CURRENT_TIMESTAMP(3) WHERE user_id=? AND done_at IS NULL",user);
                    engine.db.update("UPDATE cx_user SET copy_revision=? WHERE user_id=?",revision,user);return null;
                });
                done+=sourceTx.execute(s->{var current=source.queryForMap("SELECT * FROM ux_core_copy_user WHERE user_id=? FOR UPDATE",user);if(!owner.equals(current.get("owner"))||CoreEngine.number(current,"revision")!=revision||!((Timestamp)current.get("lease_until")).after(engine.stock.now()))return 0;
                    importIndexes(user,data);return source.update("UPDATE ux_core_copy_user SET applied_revision=?,last_error=NULL WHERE user_id=? AND owner=? AND revision=?",revision,user,owner,revision);});
            }catch(RuntimeException failure){source.update("UPDATE ux_core_copy_user SET last_error='COPY_FAILED',next_at=TIMESTAMPADD(SECOND,2,CURRENT_TIMESTAMP(3)) WHERE user_id=? AND owner=?",user,owner);throw failure;}
            finally{source.update("UPDATE ux_core_copy_user SET owner=NULL,lease_until=NULL WHERE user_id=? AND owner=?",user,owner);}
        }return done;
    }
    Map<String,List<Map<String,Object>>> read(long user){return snapshot.execute(s->{
        var result=new LinkedHashMap<String,List<Map<String,Object>>>();var account=source.queryForList("SELECT id user_id,username,display_name,created_at FROM ux_account WHERE id=?",user);if(account.isEmpty())throw new Problem(409,"MIGRATION_ACCOUNT_MISSING");result.put("cx_user",account);
        var orders=source.queryForList("SELECT * FROM ux_order WHERE user_id=? ORDER BY id LIMIT 10001",user);var byRequest=new HashMap<String,Object>();orders.forEach(r->byRequest.put(r.get("request_id").toString(),r.get("id")));
        var requests=source.queryForList("SELECT * FROM ux_request WHERE user_id=? ORDER BY id LIMIT 10001",user);for(var r:requests)r.put("order_id",byRequest.getOrDefault(r.get("id").toString(),UUID.nameUUIDFromBytes(("core-order:"+r.get("id")).getBytes(StandardCharsets.UTF_8)).toString()));
        result.put("cx_request",requests);result.put("cx_order",orders);
        result.put("cx_outbox",source.queryForList("SELECT b.*,r.user_id FROM ux_outbox b JOIN ux_request r ON r.id=b.request_id WHERE r.user_id=? LIMIT 10001",user));
        result.put("cx_payment",source.queryForList("SELECT * FROM ux_payment WHERE user_id=? LIMIT 10001",user));
        result.put("cx_receipt",source.queryForList("SELECT r.*,p.user_id FROM ux_payment_receipt r JOIN ux_payment p ON p.id=r.payment_id WHERE p.user_id=? LIMIT 10001",user));
        result.put("cx_refund",source.queryForList("SELECT r.*,p.user_id FROM ux_refund r JOIN ux_payment p ON p.id=r.payment_id WHERE p.user_id=? LIMIT 10001",user));
        result.put("cx_close",source.queryForList("SELECT * FROM ux_close_task WHERE user_id=? LIMIT 10001",user));
        if(result.values().stream().anyMatch(rows->rows.size()>10000))throw new Problem(409,"MIGRATION_USER_BATCH_REQUIRED");return result;
    });}
    static void upsert(JdbcTemplate db,String table,List<String> columns,Map<String,Object> row){
        String assignments=columns.stream().filter(c->!List.of("id","user_id","channel_id","request_id").contains(c)).map(c->c+"=VALUES("+c+")").collect(java.util.stream.Collectors.joining(","));
        if(assignments.isEmpty())assignments=columns.get(0)+"=VALUES("+columns.get(0)+")";
        db.update("INSERT INTO "+table+"("+String.join(",",columns)+") VALUES("+String.join(",",Collections.nCopies(columns.size(),"?"))+") ON DUPLICATE KEY UPDATE "+assignments,columns.stream().map(row::get).toArray());
    }
    private void importIndexes(long user,Map<String,List<Map<String,Object>>> data){
        for(var r:data.get("cx_order")){
            String state=List.of("CANCELLED","EXPIRED").contains(r.get("state"))?"RELEASED":"HELD";
            source.update("INSERT INTO ux_core_stock_hold(request_id,user_id,activity_id,price_cents,state,created_at,released_at) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE state=VALUES(state),released_at=VALUES(released_at)",r.get("request_id"),user,r.get("activity_id"),r.get("price_cents"),state,r.get("created_at"),state.equals("RELEASED")?engine.stock.now():null);
            source.update("INSERT IGNORE INTO ux_core_order_route(order_id,user_id,request_id,created_at) VALUES(?,?,?,?)",r.get("id"),user,r.get("request_id"),r.get("created_at"));
        }
        for(var r:data.get("cx_receipt")){
            source.update("INSERT IGNORE INTO ux_core_channel_claim(channel_id,payment_id,user_id,amount_cents,created_at,processed_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP(3))",r.get("channel_id"),r.get("payment_id"),user,r.get("amount_cents"),r.get("created_at"));
            if(source.queryForObject("SELECT COUNT(*) FROM ux_sandbox_refund WHERE channel_id=?",Integer.class,r.get("channel_id"))>0)source.update("INSERT IGNORE INTO ux_core_provider_refund(channel_id,amount_cents,created_at) VALUES(?,?,CURRENT_TIMESTAMP(3))",r.get("channel_id"),r.get("amount_cents"));
        }
    }
    public Map<String,Object> verify(){
        if(!List.of("MIGRATING","PAUSED").contains(mode()))throw new Problem(409,"MIGRATION_MODE_REQUIRED");long checked=0,differences=0;var expected=new LinkedHashMap<String,Long>();COLUMNS.keySet().forEach(t->expected.put(t,0L));var details=new ArrayList<Map<String,Object>>();
        for(long user:source.queryForList("SELECT id FROM ux_account ORDER BY id",Long.class)){
            var data=read(user);for(var entry:data.entrySet()){
                String table=entry.getKey();var target=engine.db.queryForList("SELECT "+String.join(",",COLUMNS.get(table))+" FROM "+table+" WHERE user_id=?",user);
                expected.put(table,expected.get(table)+entry.getValue().size());
                checked+=entry.getValue().size();if(!sameRows(entry.getValue(),target,COLUMNS.get(table))){differences++;details.add(Map.of("table",table,"user",Long.toString(user),"sourceRows",entry.getValue().size(),"targetRows",target.size()));}
            }
        }
        for(String table:COLUMNS.keySet()){long actual=0;for(int shard=0;shard<32;shard++)actual+=engine.store.physical(shard).queryForObject("SELECT COUNT(*) FROM "+CoreStore.table(table,shard),Long.class);if(actual!=expected.get(table)){differences++;details.add(Map.of("table",table,"expected",expected.get(table),"physicalRows",actual));}}
        long sourceOrders=source.queryForObject("SELECT COUNT(*) FROM ux_order",Long.class);var topology=engine.store.topology();boolean pass=differences==0&&pending()==0&&topology.get("orders").equals(sourceOrders)&&topology.get("misplaced").equals(0L);
        source.update("INSERT INTO ux_core_migration(name,done) VALUES('verified',?) ON DUPLICATE KEY UPDATE done=VALUES(done),changed_at=CURRENT_TIMESTAMP(3)",pass);
        return Map.of("passed",pass,"checkedRows",checked,"differentFamilies",differences,"pending",pending(),"topology",topology,"details",details);
    }
    static boolean sameRows(List<Map<String,Object>> left,List<Map<String,Object>> right,List<String> columns){
        if(left.size()!=right.size())return false;String key=columns.contains("id")?"id":columns.contains("channel_id")?"channel_id":columns.contains("request_id")?"request_id":"user_id";
        var index=new HashMap<Object,Map<String,Object>>();right.forEach(r->index.put(r.get(key),r));for(var l:left){var r=index.get(l.get(key));if(r==null)return false;for(String c:columns){if(c.equals("order_id")&&!l.getOrDefault("state","").equals("SUCCEEDED"))continue;if(!Objects.equals(l.get(c),r.get(c)))return false;}}return true;
    }
    public void activate(){
        if(!List.of("LEGACY","MIGRATING","PAUSED").contains(mode()))throw new Problem(409,"ALREADY_CORE");
        source.update("UPDATE ux_core_control SET mode='PAUSED',changed_at=CURRENT_TIMESTAMP(3) WHERE id=1");source.update("INSERT INTO ux_core_copy_user(user_id) SELECT id FROM ux_account ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)");
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);while(pending()>0&&System.nanoTime()<deadline)if(drain(20)==0)break;
        if(!Boolean.TRUE.equals(verify().get("passed")))throw new Problem(409,"CORE_MIGRATION_NOT_VERIFIED");
        new CoreRecovery(engine).all();source.update("INSERT INTO ux_core_migration(name,done) VALUES('activated',TRUE) ON DUPLICATE KEY UPDATE done=TRUE,changed_at=CURRENT_TIMESTAMP(3)");source.update("UPDATE ux_core_control SET mode='CORE',changed_at=CURRENT_TIMESTAMP(3) WHERE id=1");
    }
    public Map<String,Object> rollback(org.springframework.data.redis.core.StringRedisTemplate redis,Transactions tx){
        if(!"PAUSED".equals(mode()))throw new Problem(409,"STOP_WRITERS_AND_PAUSE_BEFORE_ROLLBACK");
        for(int shard=0;shard<32;shard++)if(engine.store.physical(shard).queryForObject("SELECT COUNT(*) FROM cx_request_"+shard%8+" WHERE state='ACCEPTED'",Long.class)>0)throw new Problem(409,"DRAIN_ACCEPTED_BEFORE_ROLLBACK");
        if(source.queryForObject("SELECT COUNT(*) FROM ux_core_channel_claim WHERE processed_at IS NULL",Long.class)>0)throw new Problem(409,"RECOVER_RECEIPTS_BEFORE_ROLLBACK");
        new CoreRecovery(engine).all();long users=0;
        for(long user:source.queryForList("SELECT id FROM ux_account ORDER BY id",Long.class)){
            engine.user(user);var data=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:COLUMNS.keySet())data.put(table,engine.db.queryForList("SELECT * FROM "+table+" WHERE user_id=?",user));
            sourceTx.execute(s->{
                for(var r:data.get("cx_request")){r.put("payload_hash",CoreEngine.hash(CoreEngine.number(r,"activity_id")));upsert(source,"ux_request",List.of("id","user_id","request_key","activity_id","payload_hash","state","reason","created_at","deadline","completed_at"),r);}
                for(var r:data.get("cx_order")){upsert(source,"ux_order",COLUMNS.get("cx_order"),r);source.update("INSERT INTO ux_order_projection(order_id,revision,applied_revision,changed_at) VALUES(?,1,0,CURRENT_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE revision=revision+1,changed_at=CURRENT_TIMESTAMP(3)",r.get("id"));}
                for(var r:data.get("cx_outbox")){upsert(source,"ux_outbox",List.of("id","request_id","activity_id","created_at","sent_at","next_at"),r);source.update("UPDATE ux_outbox SET owner=NULL,lease_until=NULL WHERE id=?",r.get("id"));}
                for(var r:data.get("cx_payment"))upsert(source,"ux_payment",COLUMNS.get("cx_payment"),r);
                for(var r:data.get("cx_receipt"))upsert(source,"ux_payment_receipt",List.of("channel_id","payment_id","amount_cents","outcome","created_at"),r);
                for(var r:data.get("cx_refund"))upsert(source,"ux_refund",List.of("channel_id","payment_id","amount_cents","attempts","next_at","done_at"),r);
                for(var r:data.get("cx_close")){r.put("next_enqueue_at",engine.stock.now());r.put("message_sent_at",null);upsert(source,"ux_close_task",List.of("request_id","user_id","due_at","done_at","next_enqueue_at","message_sent_at"),r);}
                source.update("INSERT IGNORE INTO ux_sandbox_refund(channel_id,amount_cents,created_at) SELECT f.channel_id,f.amount_cents,f.created_at FROM ux_core_provider_refund f JOIN ux_core_channel_claim c ON c.channel_id=f.channel_id WHERE c.user_id=?",user);
                return null;
            });users++;
        }
        source.update("UPDATE ux_core_copy_user SET applied_revision=revision,owner=NULL,lease_until=NULL");var verification=verify();if(!Boolean.TRUE.equals(verification.get("passed")))throw new Problem(409,"ROLLBACK_NOT_VERIFIED");
        int rebuilt=LegacyReservationRecovery.rebuild(source,tx,redis);source.update("UPDATE ux_core_control SET mode='LEGACY',changed_at=CURRENT_TIMESTAMP(3) WHERE id=1");
        return Map.of("mode","LEGACY","users",users,"redisActivities",rebuilt,"verification",verification);
    }
}
