package com.hmdp.upgrade;

import com.hmdp.upgrade.sharding.ShardedHistoryReplica;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.net.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SHARDING_INTEGRATION",matches="true")
class ExternalOrderHistoryTest {
    static ShardedHistoryReplica target;
    JdbcTemplate db;Transactions tx;Trading trading;OrderProjection local;ExternalOrderHistory history;
    long activity;
    @BeforeAll static void init() throws Exception {
        new ShardingEnvironmentTest().isolatedInstancesAndSourceMigrationsAreReady();
        target=new ShardedHistoryReplica(new String[]{ShardingEnvironmentTest.url(33317,"qupiao_history"),ShardingEnvironmentTest.url(33318,"qupiao_history")},"qupiao",System.getenv("SHARDING_DB_PASSWORD"));
    }
    @AfterAll static void close() throws Exception { if(target!=null)target.close(); }
    @BeforeEach void setup() {
        db=new JdbcTemplate(ShardingEnvironmentTest.source(33307,"qupiao_sharding_source"));
        tx=new Transactions(new DataSourceTransactionManager(db.getDataSource()));trading=new Trading(db,tx);local=new OrderProjection(db);
        ReflectionTestUtils.setField(trading,"projection",local);history=new ExternalOrderHistory(db,tx,target);
        db.update("UPDATE ux_history_control SET read_mode='LOCAL',backfill_cursor='',backfill_done=FALSE,lease_owner=NULL,lease_until=NULL,verify_cursor='',verify_checked=0,verify_mismatches=0,verified_at=NULL WHERE id=1");
        activity=Math.floorMod(UUID.randomUUID().getMostSignificantBits(),1_000_000_000_000L)+100;
        var now=Instant.now();db.update("INSERT INTO ux_activity VALUES(?,100,100,990,?,?,?)",activity,Timestamp.from(now.minusSeconds(60)),Timestamp.from(now.plusSeconds(600)),Timestamp.from(now.plusSeconds(900)));
    }
    Trading.Request order(long user) {return trading.accept(user,UUID.randomUUID().toString(),activity,true);}
    @AfterEach void cleanup() {
        if(db==null)return;
        for(var row:db.queryForList("SELECT id,user_id FROM ux_order WHERE activity_id=?",activity)) {
            target.jdbc().update("DELETE FROM ux_order_history WHERE user_id=? AND id=?",row.get("user_id"),row.get("id"));
            db.update("DELETE FROM "+HistoryRows.table(((Number)row.get("user_id")).longValue())+" WHERE id=?",row.get("id"));
            db.update("DELETE FROM ux_order_projection WHERE order_id=?",row.get("id"));
        }
        db.update("DELETE FROM ux_reservation_action WHERE request_id IN (SELECT id FROM ux_request WHERE activity_id=?)",activity);
        db.update("DELETE FROM ux_order WHERE activity_id=?",activity);
        db.update("DELETE FROM ux_outbox WHERE activity_id=?",activity);
        db.update("DELETE FROM ux_request WHERE activity_id=?",activity);
        db.update("DELETE FROM ux_activity WHERE id=?",activity);
        db.update("UPDATE ux_history_control SET read_mode='LOCAL',lease_owner=NULL,lease_until=NULL WHERE id=1");
    }
    void finishBackfill() { while(!Boolean.TRUE.equals(history.backfill(2).get("done"))) {} }
    Map<String,Object> finishVerify() {
        Map<String,Object> result;do {result=history.verify(2);}while(!Boolean.TRUE.equals(result.get("done")));return result;
    }
    @Test void commitIntentIsSeparateFromRemoteWriteAndRollbackRemovesIntent() {
        order(33);assertEquals(0,history.count(33));assertEquals(1,history.pending());
        local.drain();assertEquals(1,local.count(33));assertEquals(1,history.pending());
        assertEquals(1,history.drain(20));assertEquals(1,history.count(33));
        assertEquals(local.list(33,1),history.list(33,1));assertEquals(0,history.count(1));
        assertThrows(IllegalStateException.class,()->tx.run(()->{order(44);throw new IllegalStateException();}));
        assertEquals(0,history.count(44));assertEquals(0,history.pending());
    }
    @Test void targetCommitBeforeSourceAckRetriesAndOldVersionCannotRegress() {
        var request=order(8);var old=new HashMap<>(db.queryForMap(ExternalOrderHistory.SNAPSHOT+" WHERE o.request_id=?",request.id()));
        var fault=new DelegatingReplica() {boolean once=true;@Override public void apply(Map<String,Object> row) {
            target.apply(row);if(once){once=false;throw new IllegalStateException("simulated target commit before source ack");}
        }};
        var interrupted=new ExternalOrderHistory(db,tx,fault);
        assertEquals(0,interrupted.drain(20));assertEquals(1,history.pending());assertEquals(1,history.count(8));
        db.update("UPDATE ux_order_projection SET replica_next_at=CURRENT_TIMESTAMP(3) WHERE order_id=?",old.get("id"));
        assertEquals(1,history.drain(20));assertEquals(1,history.count(8));
        trading.transition(8,request.id(),"confirm");history.drain(20);target.apply(old);
        assertEquals("CONFIRMED",history.list(8,1).get(0).state());
    }
    @Test void unavailableTargetDoesNotUndoTradingOrLocalProjection() {
        var unavailable=new ExternalOrderHistory(db,tx,new DelegatingReplica(){@Override public void apply(Map<String,Object> row){throw new IllegalStateException("offline");}});
        var request=order(17);assertEquals(0,unavailable.drain(20));
        assertEquals("SUCCEEDED",trading.result(17,request.id()).state());local.drain();assertEquals(1,local.count(17));
        assertEquals(1,history.pending());
        db.update("UPDATE ux_order_projection SET replica_next_at=CURRENT_TIMESTAMP(3) WHERE order_id IN (SELECT id FROM ux_order WHERE activity_id=?)",activity);
        history.drain(20);assertEquals(1,history.count(17));
    }
    @Test void resumableBackfillVerificationCutoverAndRollbackUseSameAccount() {
        for(long user=100;user<105;user++)order(user);local.drain();
        assertThrows(Problem.class,()->history.readMode("SHARDED",1));
        assertEquals(2,history.backfill(2).get("count"));
        history=new ExternalOrderHistory(db,tx,target);finishBackfill();history.drain(20);
        assertEquals(Boolean.TRUE,finishVerify().get("passed"));history.readMode("SHARDED",1);assertTrue(history.readsShards());
        var api=new OrderProjectionApi(local);ReflectionTestUtils.setField(api,"external",history);
        var request=new MockHttpServletRequest();request.setAttribute("identity",new Auth.Identity(101,false));
        assertEquals(local.list(101,1),api.list(request,1).items());
        request.setParameter("userId","102");assertEquals(local.list(101,1),api.list(request,1).items());
        history.readMode("LOCAL",1);assertFalse(history.readsShards());assertEquals(local.list(101,1),api.list(request,1).items());
        assertThrows(Problem.class,()->history.list(0,1));assertThrows(Problem.class,()->history.list(101,501));
    }
    @Test void verificationRepairsMissingRowsAndEqualVersionCorruption() {
        var request=order(19);history.drain(20);finishBackfill();assertEquals(Boolean.TRUE,finishVerify().get("passed"));
        target.jdbc().update("UPDATE ux_order_history SET price_cents=1,request_id='corrupted',activity_id=999,created_at='2000-01-01' WHERE user_id=?",19L);
        assertEquals(Boolean.FALSE,finishVerify().get("passed"));assertEquals(1,history.pending());history.drain(20);
        var repaired=history.list(19,1).get(0);
        assertEquals(990,repaired.priceCents());assertEquals(request.id(),repaired.requestId());assertEquals(activity,repaired.activityId());
        assertEquals(db.queryForObject("SELECT created_at FROM ux_order WHERE request_id=?",Timestamp.class,request.id()).toInstant(),repaired.createdAt());
        assertEquals(Boolean.TRUE,finishVerify().get("passed"));
        target.jdbc().update("DELETE FROM ux_order_history WHERE user_id=?",19L);
        assertEquals(Boolean.FALSE,finishVerify().get("passed"));history.drain(20);
        assertEquals(Boolean.TRUE,finishVerify().get("passed"));
    }
    @Test void realtimeChangeDuringBackfillWinsAndExpiredWorkerCannotAdvanceCursor() {
        var request=order(23);
        var interleaved=new DelegatingReplica(){boolean once=true;@Override public void apply(Map<String,Object> row){
            if(once){once=false;trading.transition(23,request.id(),"confirm");}target.apply(row);
        }};
        var worker=new ExternalOrderHistory(db,tx,interleaved);worker.backfill(1);history.drain(20);
        assertEquals("CONFIRMED",history.list(23,1).get(0).state());
        db.update("UPDATE ux_history_control SET backfill_cursor='',backfill_done=FALSE WHERE id=1");
        var stale=new ExternalOrderHistory(db,tx,new DelegatingReplica(){@Override public void apply(Map<String,Object> row){
            target.apply(row);db.update("UPDATE ux_history_control SET lease_owner='replacement' WHERE id=1");
        }});
        assertEquals(Boolean.FALSE,stale.backfill(1).get("checkpointSaved"));
        assertEquals("",db.queryForObject("SELECT backfill_cursor FROM ux_history_control WHERE id=1",String.class));
        db.update("UPDATE ux_history_control SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE id=1");
        finishBackfill();assertEquals(1,history.count(23));
    }
    @Test void realTcpDisconnectRecoversWithoutBlockingTrading() throws Exception {
        try(var proxy=new DisconnectProxy(33317);
            var networkTarget=new ShardedHistoryReplica(new String[]{ShardingEnvironmentTest.url(proxy.port(),"qupiao_history"),ShardingEnvironmentTest.url(33318,"qupiao_history")},"qupiao",System.getenv("SHARDING_DB_PASSWORD"))) {
            var networkHistory=new ExternalOrderHistory(db,tx,networkTarget);
            proxy.down();
            var request=order(8);assertEquals("SUCCEEDED",trading.result(8,request.id()).state());
            assertEquals(0,networkHistory.drain(20));assertEquals(1,networkHistory.pending());
            local.drain();assertEquals(1,local.count(8));
            db.update("UPDATE ux_history_control SET read_mode='SHARDED' WHERE id=1");
            new ExternalOrderHistory(db,tx,(HistoryReplica)null).readMode("LOCAL",0);
            assertEquals("LOCAL",db.queryForObject("SELECT read_mode FROM ux_history_control WHERE id=1",String.class));
            proxy.up();
            var id=db.queryForObject("SELECT id FROM ux_order WHERE request_id=?",String.class,request.id());
            db.update("UPDATE ux_order_projection SET replica_next_at=CURRENT_TIMESTAMP(3) WHERE order_id=?",id);
            long deadline=System.nanoTime()+15_000_000_000L;
            while(networkHistory.pending()>0 && System.nanoTime()<deadline) {
                db.update("UPDATE ux_order_projection SET replica_next_at=CURRENT_TIMESTAMP(3) WHERE order_id=?",id);
                networkHistory.drain(20);
            }
            assertEquals(0,networkHistory.pending());assertEquals(1,history.count(8));
        }
    }
    @Test void extraRowsAndWrongPhysicalRoutesPreventCutover() {
        var request=order(25);history.drain(20);finishBackfill();assertEquals(Boolean.TRUE,finishVerify().get("passed"));
        var extra=new HashMap<>(db.queryForMap(ExternalOrderHistory.SNAPSHOT+" WHERE o.request_id=?",request.id()));
        String extraId=UUID.randomUUID().toString();extra.put("id",extraId);extra.put("request_id",extraId);extra.put("user_id",26L);
        var physical=new JdbcTemplate(ShardingEnvironmentTest.source(33318,"qupiao_history"));
        try {
            target.apply(extra);
            assertEquals(Boolean.FALSE,finishVerify().get("passed"));assertThrows(Problem.class,()->history.readMode("SHARDED",1));
            target.jdbc().update("DELETE FROM ux_order_history WHERE user_id=? AND id=?",26L,extraId);
            HistoryRows.apply(physical,"ux_order_read_27",extra);
            var result=finishVerify();assertEquals(Boolean.FALSE,result.get("passed"));
            @SuppressWarnings("unchecked") var topology=(Map<String,Long>)result.get("topology");assertEquals(1L,topology.get("misplaced"));
            assertThrows(Problem.class,()->history.readMode("SHARDED",1));
        } finally {
            target.jdbc().update("DELETE FROM ux_order_history WHERE user_id=? AND id=?",26L,extraId);
            physical.update("DELETE FROM ux_order_read_27 WHERE id=?",extraId);
        }
        assertEquals(Boolean.TRUE,finishVerify().get("passed"));
    }
    static final class DisconnectProxy implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService workers=Executors.newCachedThreadPool();
        private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        private volatile boolean blocked,closed;
        DisconnectProxy(int targetPort) throws Exception {
            server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));
            workers.submit(()->{
                while(!closed)try {
                    Socket client=server.accept();
                    if(blocked){client.close();continue;}
                    Socket upstream=new Socket("127.0.0.1",targetPort);sockets.add(client);sockets.add(upstream);
                    workers.submit(()->copy(client,upstream));workers.submit(()->copy(upstream,client));
                }catch(Exception failure){if(closed)break;}
            });
        }
        int port(){return server.getLocalPort();}
        void down(){blocked=true;for(Socket socket:sockets)try{socket.close();}catch(Exception ignored){}sockets.clear();}
        void up(){blocked=false;}
        void copy(Socket from,Socket to){
            try{from.getInputStream().transferTo(to.getOutputStream());}catch(Exception ignored){}
            finally{try{from.close();to.close();}catch(Exception ignored){}sockets.remove(from);sockets.remove(to);}
        }
        public void close() throws Exception{closed=true;down();server.close();workers.shutdownNow();}
    }
    static class DelegatingReplica implements HistoryReplica {
        public JdbcTemplate jdbc(){return target.jdbc();}
        public void apply(Map<String,Object> row){target.apply(row);}
        public Map<String,Long> audit(){return target.audit();}
    }
}
