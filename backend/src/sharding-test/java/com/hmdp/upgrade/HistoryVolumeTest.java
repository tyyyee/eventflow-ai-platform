package com.hmdp.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.upgrade.sharding.ShardedHistoryReplica;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import com.zaxxer.hikari.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SHARDING_INTEGRATION",matches="true")
class HistoryVolumeTest {
    @Test void tenThousandSyntheticOrdersBackfillResumeAndVerify() throws Exception {
        new ShardingEnvironmentTest().isolatedInstancesAndSourceMigrationsAreReady();
        var config=new HikariConfig();config.setJdbcUrl(ShardingEnvironmentTest.url(33307,"qupiao_sharding_source"));
        config.setUsername("qupiao");config.setPassword(System.getenv("SHARDING_DB_PASSWORD"));config.setMaximumPoolSize(4);config.setMinimumIdle(0);
        try(var sourcePool=new HikariDataSource(config)) {
        var db=new JdbcTemplate(sourcePool);
        var tx=new Transactions(new DataSourceTransactionManager(db.getDataSource()));
        var local=new OrderProjection(db);var trading=new Trading(db,tx);ReflectionTestUtils.setField(trading,"projection",local);
        String batch=UUID.randomUUID().toString().substring(0,8);
        long activity=Math.floorMod(UUID.randomUUID().getMostSignificantBits(),1_000_000_000_000L)+100;
        int size=10000;long began=System.nanoTime();
        try(var replica=new ShardedHistoryReplica(new String[]{ShardingEnvironmentTest.url(33317,"qupiao_history"),ShardingEnvironmentTest.url(33318,"qupiao_history")},"qupiao",System.getenv("SHARDING_DB_PASSWORD"))) {
            try {
                // Recover only deterministic fixtures owned by this isolated volume harness.
                for(var old:db.queryForList("SELECT activity_id,SUBSTRING(id,1,8) AS batch FROM ux_request WHERE payload_hash='fixture' AND id REGEXP '^[a-f0-9]{8}-0000-0000-0000-[0-9]{12}$' GROUP BY activity_id,SUBSTRING(id,1,8)"))
                    cleanup(db,old.get("batch").toString(),((Number)old.get("activity_id")).longValue());
                assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM ux_order",Long.class));
                db.update("UPDATE ux_history_control SET read_mode='LOCAL',backfill_cursor='',backfill_done=FALSE,lease_owner=NULL,lease_until=NULL,verify_cursor='',verify_checked=0,verify_mismatches=0,verified_at=NULL WHERE id=1");
                var now=Instant.now();
                db.update("INSERT INTO ux_activity VALUES(?,?,0,990,?,?,?)",activity,size,Timestamp.from(now.minusSeconds(60)),Timestamp.from(now.plusSeconds(3600)),Timestamp.from(now.plusSeconds(7200)));
                for(int start=1;start<=size;start+=500) {
                    var requests=new ArrayList<Object[]>();var orders=new ArrayList<Object[]>();var intents=new ArrayList<Object[]>();
                    for(int i=start;i<Math.min(start+500,size+1);i++) {
                        String id=id(batch,i);long user=1_000_000L+i;
                        requests.add(new Object[]{id,user,id,activity,"fixture","SUCCEEDED",Timestamp.from(now.minusSeconds(i)),Timestamp.from(now.plusSeconds(7200)),Timestamp.from(now.minusSeconds(i))});
                        orders.add(new Object[]{id,id,activity,user,990,"PENDING_CONFIRM",Timestamp.from(now.plusSeconds(3600)),Timestamp.from(now.minusSeconds(i))});
                        intents.add(new Object[]{id});
                    }
                    tx.run(()->{
                        db.batchUpdate("INSERT INTO ux_request(id,user_id,request_key,activity_id,payload_hash,state,created_at,deadline,completed_at) VALUES(?,?,?,?,?,?,?,?,?)",requests);
                        db.batchUpdate("INSERT INTO ux_order(id,request_id,activity_id,user_id,price_cents,state,confirm_until,created_at) VALUES(?,?,?,?,?,?,?,?)",orders);
                        db.batchUpdate("INSERT INTO ux_order_projection(order_id,revision,applied_revision,changed_at) VALUES(?,1,0,CURRENT_TIMESTAMP(3))",intents);return null;
                    });
                }
                for(int i=0;i<110 && local.drain()>0;i++) {}
                var history=new ExternalOrderHistory(db,tx,replica);
                history.backfill(100);String checkpoint=history.status().get("backfill_cursor").toString();assertFalse(checkpoint.isEmpty());
                history=new ExternalOrderHistory(db,tx,replica);assertEquals(checkpoint,history.status().get("backfill_cursor"));
                trading.transition(1_000_001L,id(batch,1),"confirm");
                boolean done=false;int batches=1;
                for(int i=0;i<110;i++){batches++;if(Boolean.TRUE.equals(history.backfill(100).get("done"))){done=true;break;}}
                assertTrue(done);
                for(int i=0;i<110 && history.pending()>0;i++)history.drain(100);
                assertEquals(0,history.pending());Map<String,Object> verification=null;
                for(int i=0;i<110;i++){verification=history.verify(100);if(Boolean.TRUE.equals(verification.get("done")))break;}
                assertNotNull(verification);assertEquals(Boolean.TRUE,verification.get("passed"));assertEquals(10000L,verification.get("checked"));
                assertEquals(Map.of("rows",10000L,"misplaced",0L),replica.audit());
                assertEquals("CONFIRMED",history.list(1_000_001L,1).get(0).state());
                for(int user=1;user<=32;user++)assertEquals(1,history.count(1_000_000L+user));
                assertEquals(0L,trading.invariants().get("stockViolations"));
                history.readMode("SHARDED",1);assertTrue(history.readsShards());history.readMode("LOCAL",1);
                Path path=Path.of("../docs/verification/2026-10-27-sharding/volume.json");
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),Map.of(
                    "syntheticOrders",size,"backfillBatches",batches,"checkpointResume",true,"concurrentSourceChange",true,
                    "misplacedRows",0,"differences",0,"elapsedSeconds",(System.nanoTime()-began)/1e9,
                    "scope","Synthetic historical fixture; this is not a seckill throughput test."));
            } finally {
                cleanup(db,batch,activity);
                db.update("UPDATE ux_history_control SET read_mode='LOCAL' WHERE id=1");
            }
        }
        }
    }
    static void cleanup(JdbcTemplate db,String batch,long activity) {
        if(!batch.matches("[a-f0-9]{8}"))throw new IllegalArgumentException("Invalid fixture ownership");
        for(int shard=0;shard<32;shard++) {
                    var physical=new JdbcTemplate(ShardingEnvironmentTest.source(shard<16?33317:33318,"qupiao_history"));
                    physical.update("DELETE FROM ux_order_read_"+String.format(Locale.ROOT,"%02d",shard)+" WHERE id LIKE ?",batch+"-%");
                    db.update("DELETE FROM ux_order_read_"+String.format(Locale.ROOT,"%02d",shard)+" WHERE id LIKE ?",batch+"-%");
                }
                db.update("DELETE FROM ux_order_projection WHERE order_id LIKE ?",batch+"-%");
                db.update("DELETE FROM ux_reservation_action WHERE request_id LIKE ?",batch+"-%");
                db.update("DELETE FROM ux_order WHERE activity_id=?",activity);
                db.update("DELETE FROM ux_request WHERE activity_id=?",activity);
                db.update("DELETE FROM ux_activity WHERE id=?",activity);
    }
    static String id(String batch,long index) {return batch+"-0000-0000-0000-"+String.format(Locale.ROOT,"%012d",index);}
}
