package com.hmdp.upgrade.core32;

import com.hmdp.upgrade.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.zaxxer.hikari.HikariDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named="CORE32_INTEGRATION",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Core32TradingTest {
    HikariDataSource source;CoreStore store;JdbcTemplate catalog;LeasedIds ids;CoreEngine engine;CoreJobs jobs;
    LettuceConnectionFactory factory;StringRedisTemplate redis;final List<Long> users=new ArrayList<>(),activities=new ArrayList<>();
    long serial=System.currentTimeMillis()*100;
    @BeforeAll void setup()throws Exception{
        source=CoreMain.source();Flyway.configure().dataSource(source).load().migrate();catalog=new JdbcTemplate(source);store=new CoreStore(CoreMain.urls(),"qupiao",System.getenv("CORE32_PASSWORD"),true);
        var config=new RedisStandaloneConfiguration("127.0.0.1",36380);config.setPassword(System.getenv("CORE32_REDIS_PASSWORD"));config.setDatabase(12);factory=new LettuceConnectionFactory(config);factory.afterPropertiesSet();factory.start();redis=new StringRedisTemplate(factory);
        ids=new LeasedIds(catalog);var tx=new Transactions(new DataSourceTransactionManager(source));engine=new CoreEngine(store,new CoreStock(catalog,tx),new CoreRedis(redis),ids,tx);
        jobs=new CoreJobs(engine,mock(OrderMessages.class),new ObjectMapper(),"core32-test-unused",false);catalog.update("UPDATE ux_core_control SET mode='CORE' WHERE id=1");
    }
    @BeforeEach void renew(){ids.renew();engine.fault=point->{};}
    long user(){long user=++serial;users.add(user);catalog.update("INSERT INTO ux_account(id,username,display_name,password_hash,created_at) VALUES(?,?,?,'fixture-only',CURRENT_TIMESTAMP(3))",user,"ct_"+user,"Core test");return user;}
    long activity(int stock){long id=++serial;activities.add(id);catalog.update("INSERT INTO ux_activity(id,capacity,available,price_cents,starts_at,ends_at,process_until) VALUES(?,?,?,990,TIMESTAMPADD(SECOND,-60,CURRENT_TIMESTAMP(3)),TIMESTAMPADD(HOUR,2,CURRENT_TIMESTAMP(3)),TIMESTAMPADD(HOUR,3,CURRENT_TIMESTAMP(3)))",id,stock,stock);engine.prepare(id);jobs.catalog();return id;}
    CoreEngine.Request purchase(long user,long activity){return engine.purchase(user,"core_"+UUID.randomUUID(),activity);}
    void actions(long user){for(var task:engine.db.queryForList("SELECT * FROM cx_action WHERE user_id=? AND done_at IS NULL",user))engine.apply(task);}
    @Test void messageSendFailureAndRestartKeepAcceptedRequestRecoverable()throws Exception{
        long user=user(),activity=activity(2);var request=purchase(user,activity);var messages=mock(OrderMessages.class);
        org.mockito.Mockito.when(messages.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker offline")),CompletableFuture.completedFuture(null));
        var sender=new CoreJobs(engine,messages,new ObjectMapper(),"core32-recovery-test",false);int shard=CoreRouteAlgorithm.shard(user);
        sender.relay(shard,4);assertNull(engine.db.queryForMap("SELECT sent_at FROM cx_outbox WHERE user_id=? AND request_id=?",user,request.id()).get("sent_at"));
        engine.db.update("UPDATE cx_outbox SET next_at=CURRENT_TIMESTAMP(3) WHERE user_id=? AND request_id=?",user,request.id());sender.relay(shard,4);
        assertNotNull(engine.db.queryForMap("SELECT sent_at FROM cx_outbox WHERE user_id=? AND request_id=?",user,request.id()).get("sent_at"));
        sender.recoverPendingMessages();assertNull(engine.db.queryForMap("SELECT sent_at FROM cx_outbox WHERE user_id=? AND request_id=?",user,request.id()).get("sent_at"));
        sender.relay(shard,4);String body=new ObjectMapper().writeValueAsString(new CoreJobs.Event(2,request.id(),user,activity));
        var record=new OrderMessages.Received("core32-recovery-test","broker",0,1,Long.toString(user),body,System.currentTimeMillis());sender.consume(record);sender.consume(record);
        assertEquals(1,engine.db.queryForObject("SELECT COUNT(*) FROM cx_order WHERE user_id=? AND request_id=?",Integer.class,user,request.id()));actions(user);
    }
    @Test void closeMessageFailureAndDuplicateDeliveryRestoreStockOnce()throws Exception{
        long user=user(),activity=activity(2);var request=purchase(user,activity);engine.process(user,request.id(),activity);actions(user);
        var messages=mock(OrderMessages.class);org.mockito.Mockito.when(messages.sendAt(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker offline")),CompletableFuture.completedFuture(null));
        var sender=new CoreJobs(engine,messages,new ObjectMapper(),"core32-close-test",false);int shard=CoreRouteAlgorithm.shard(user);sender.sendCloses(shard,4);
        assertNull(engine.db.queryForMap("SELECT message_sent_at FROM cx_close WHERE user_id=? AND request_id=?",user,request.id()).get("message_sent_at"));
        engine.db.update("UPDATE cx_close SET next_enqueue_at=CURRENT_TIMESTAMP(3) WHERE user_id=? AND request_id=?",user,request.id());sender.sendCloses(shard,4);
        assertNotNull(engine.db.queryForMap("SELECT message_sent_at FROM cx_close WHERE user_id=? AND request_id=?",user,request.id()).get("message_sent_at"));
        var record=new OrderMessages.Received("core32-close-test","broker",0,1,request.id(),new ObjectMapper().writeValueAsString(new DelayedClose.Event(2,user,request.id())),System.currentTimeMillis());
        assertThrows(IllegalStateException.class,()->sender.consumeClose(record));
        var past=java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1));
        engine.db.update("UPDATE cx_order SET confirm_until=? WHERE user_id=? AND request_id=?",past,user,request.id());
        engine.db.update("UPDATE cx_close SET due_at=? WHERE user_id=? AND request_id=?",past,user,request.id());
        sender.consumeClose(record);sender.consumeClose(record);actions(user);actions(user);
        engine.db.update("UPDATE cx_close SET done_at=NULL WHERE user_id=? AND request_id=?",user,request.id());sender.consumeClose(record);
        assertNotNull(engine.db.queryForMap("SELECT done_at FROM cx_close WHERE user_id=? AND request_id=?",user,request.id()).get("done_at"));
        assertEquals("EXPIRED",engine.order(user,request.id()).get("state"));assertEquals(2,engine.redis.remaining(activity));
        assertEquals(1,engine.db.queryForObject("SELECT COUNT(*) FROM cx_action WHERE user_id=? AND request_id=? AND kind='RESTORE'",Integer.class,user,request.id()));assertEquals(0L,engine.stock.invariants().get("stockViolations"));
    }
    @Test void realRocketRoutesOrdersAcrossFourDatabasesAndDelaysClose()throws Exception{
        Assumptions.assumeTrue("true".equals(System.getenv("ROCKETMQ_INTEGRATION")));
        String topic="core32-it-"+UUID.randomUUID().toString().replace("-","");
        var bus=new RocketMessageBus(System.getenv().getOrDefault("ROCKETMQ_NAMESERVER","127.0.0.1:39876"),topic+"-producer",true);
        var consumer=new CoreJobs(engine,bus,new ObjectMapper(),topic,false);String closeTopic=topic+"-close";
        org.springframework.test.util.ReflectionTestUtils.setField(consumer,"closeTopic",closeTopic);
        var orders=new CountDownLatch(4);var closed=new CountDownLatch(2);var requests=new ArrayList<CoreEngine.Request>();
        bus.subscribe(topic,topic,record->{consumer.consume(record);orders.countDown();});bus.subscribe(closeTopic,closeTopic,record->{consumer.consumeClose(record);closed.countDown();});
        try{
            bus.start();long activity=activity(4);long base=((serial+63)/32)*32;
            for(int database=0;database<4;database++){
                long user=base+database*8;users.add(user);serial=Math.max(serial,user);
                catalog.update("INSERT INTO ux_account(id,username,display_name,password_hash,created_at) VALUES(?,?,?,'fixture-only',CURRENT_TIMESTAMP(3))",user,"ct_"+user,"MQ routing test");
                var request=purchase(user,activity);requests.add(request);consumer.relay(CoreRouteAlgorithm.shard(user),4);
            }
            assertTrue(orders.await(40,TimeUnit.SECONDS));
            for(int database=0;database<4;database++){var request=requests.get(database);long user=Long.parseLong(request.userId());assertEquals(database,CoreRouteAlgorithm.shard(user)/8);assertEquals("SUCCEEDED",engine.request(user,request.id()).get("state"));actions(user);}
            var request=requests.get(0);long user=Long.parseLong(request.userId());var deadline=java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(6));
            engine.db.update("UPDATE cx_order SET confirm_until=? WHERE user_id=? AND request_id=?",deadline,user,request.id());engine.db.update("UPDATE cx_close SET due_at=? WHERE user_id=? AND request_id=?",deadline,user,request.id());
            consumer.sendCloses(CoreRouteAlgorithm.shard(user),4);
            bus.sendAt(closeTopic,request.id(),new ObjectMapper().writeValueAsString(new DelayedClose.Event(2,user,request.id())),deadline.toInstant()).get(10,TimeUnit.SECONDS);
            assertEquals("PENDING_CONFIRM",engine.order(user,request.id()).get("state"));assertTrue(closed.await(30,TimeUnit.SECONDS));
            assertEquals("EXPIRED",engine.order(user,request.id()).get("state"));actions(user);actions(user);assertEquals(1,engine.redis.remaining(activity));assertEquals(0L,engine.stock.invariants().get("stockViolations"));
        }finally{bus.stop();}
    }
    @Test void stockCommitBeforeOrderCrashIsRecoveredWithoutAnotherDecrement(){
        long user=user(),activity=activity(2);var request=purchase(user,activity);var once=new AtomicBoolean();engine.fault=point->{if(point.equals("core-after-stock-hold")&&once.compareAndSet(false,true))throw new IllegalStateException("crash after central commit");};
        assertThrows(IllegalStateException.class,()->engine.process(user,request.id(),activity));assertEquals("ACCEPTED",engine.request(user,request.id()).get("state"));
        assertEquals(1,catalog.queryForObject("SELECT available FROM ux_activity WHERE id=?",Integer.class,activity));assertEquals(0,engine.db.queryForObject("SELECT COUNT(*) FROM cx_order WHERE user_id=?",Integer.class,user));
        engine.fault=point->{};assertEquals("SUCCEEDED",engine.process(user,request.id(),activity).state());assertEquals("SUCCEEDED",engine.process(user,request.id(),activity).state());
        assertEquals(1,engine.db.queryForObject("SELECT COUNT(*) FROM cx_order WHERE user_id=?",Integer.class,user));actions(user);
        engine.transition(user,request.id(),"cancel");engine.transition(user,request.id(),"cancel");actions(user);actions(user);
        assertEquals(2,catalog.queryForObject("SELECT available FROM ux_activity WHERE id=?",Integer.class,activity));assertEquals(2,engine.redis.remaining(activity));
        assertEquals(0L,engine.stock.invariants().get("stockViolations"));
        assertEquals(1,new CoreQueries(engine).list(user,1,"").total());assertEquals(1,new CoreQueries(engine).history(user,1).get("total"));
        assertThrows(Problem.class,()->engine.order(user+32,request.id()));
    }
    @Test void expiredAcceptedRequestReleasesAnUnknownCommittedGrant(){
        long user=user(),activity=activity(1);var request=purchase(user,activity);engine.fault=point->{if(point.equals("core-after-stock-hold"))throw new IllegalStateException("unknown order commit");};
        assertThrows(IllegalStateException.class,()->engine.process(user,request.id(),activity));engine.fault=point->{};
        engine.db.update("UPDATE cx_request SET deadline=? WHERE user_id=? AND id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),user,request.id());engine.expireRequest(user,request.id());actions(user);
        assertEquals("EXPIRED",engine.process(user,request.id(),activity).state());assertEquals(1,catalog.queryForObject("SELECT available FROM ux_activity WHERE id=?",Integer.class,activity));
        assertEquals("RELEASED",catalog.queryForObject("SELECT state FROM ux_core_stock_hold WHERE request_id=?",String.class,request.id()));assertEquals(1,engine.redis.remaining(activity));
    }
    @Test void providerReceiptSurvivesCrashBeforeShardReceiptCommit(){
        long user=user(),activity=activity(1);var request=purchase(user,activity);engine.process(user,request.id(),activity);var payment=engine.createPayment(user,request.id());String channel="channel_"+UUID.randomUUID();
        engine.fault=point->{if(point.equals("core-channel-before-receipt"))throw new IllegalStateException("provider succeeded but app died");};
        assertThrows(IllegalStateException.class,()->engine.receipt(user,payment.get("id").toString(),channel,990));assertEquals(1,catalog.queryForObject("SELECT COUNT(*) FROM ux_core_channel_claim WHERE channel_id=? AND processed_at IS NULL",Integer.class,channel));
        engine.fault=point->{};jobs.channels();assertEquals("PAID",engine.payment(user,payment.get("id").toString()).get("state"));
        engine.receipt(user,payment.get("id").toString(),channel,990);assertEquals(1,engine.db.queryForObject("SELECT COUNT(*) FROM cx_receipt WHERE user_id=? AND channel_id=?",Integer.class,user,channel));actions(user);
    }
    @Test void paymentCloseRaceAndRefundRetryPreserveFinalState()throws Exception{
        long user=user(),activity=activity(1);var request=purchase(user,activity);engine.process(user,request.id(),activity);var payment=engine.createPayment(user,request.id());String channel="race_"+UUID.randomUUID();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{var paid=pool.submit(()->{start.await();return engine.receipt(user,payment.get("id").toString(),channel,990);});var cancelled=pool.submit(()->{start.await();return engine.transition(user,request.id(),"cancel");});start.countDown();paid.get(15,TimeUnit.SECONDS);cancelled.get(15,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        actions(user);var order=engine.order(user,request.id());assertTrue(List.of("CONFIRMED","CANCELLED").contains(order.get("state")));
        if(order.get("state").equals("CANCELLED")){
            var task=engine.db.queryForMap("SELECT * FROM cx_refund WHERE user_id=? AND channel_id=?",user,channel);engine.fault=point->{if(point.equals("core-refund-before-ack"))throw new IllegalStateException("refund ack lost");};assertThrows(IllegalStateException.class,()->engine.refund(task));engine.fault=point->{};engine.refund(task);engine.refund(task);
            assertEquals(1,catalog.queryForObject("SELECT COUNT(*) FROM ux_core_provider_refund WHERE channel_id=?",Integer.class,channel));assertEquals("REFUNDED",engine.payment(user,payment.get("id").toString()).get("state"));
        }
        assertEquals(0L,engine.stock.invariants().get("stockViolations"));
    }
    @Test void latePaymentRefundWithLostAcknowledgmentIsDeterministicallyIdempotent(){
        long user=user(),activity=activity(1);var request=purchase(user,activity);engine.process(user,request.id(),activity);var payment=engine.createPayment(user,request.id());engine.transition(user,request.id(),"cancel");actions(user);
        String channel="late_"+UUID.randomUUID();assertEquals("REFUND_PENDING",engine.receipt(user,payment.get("id").toString(),channel,990).get("outcome"));
        var task=engine.db.queryForMap("SELECT * FROM cx_refund WHERE user_id=? AND channel_id=?",user,channel);engine.fault=point->{if(point.equals("core-refund-before-ack"))throw new IllegalStateException("provider committed, ack lost");};
        assertThrows(IllegalStateException.class,()->engine.refund(task));engine.fault=point->{};engine.refund(task);engine.refund(task);
        assertEquals(1,catalog.queryForObject("SELECT COUNT(*) FROM ux_core_provider_refund WHERE channel_id=?",Integer.class,channel));assertEquals("REFUNDED",engine.payment(user,payment.get("id").toString()).get("state"));assertEquals(1,engine.redis.remaining(activity));
    }
    @Test void missingRedisIsRebuiltOnlyUnderMaintenanceAndSettlesKnownTerminalGrant(){
        long user=user(),activity=activity(1);var request=purchase(user,activity);engine.process(user,request.id(),activity);engine.transition(user,request.id(),"cancel");
        var recovery=new CoreRecovery(engine);assertThrows(Problem.class,()->recovery.activity(activity));
        redis.delete(List.of("cx:reserve:{"+activity+"}:stock","cx:reserve:{"+activity+"}:users","cx:reserve:{"+activity+"}:requests","cx:reserve:{"+activity+"}:pending","cx:reserve:{"+activity+"}:epoch"));
        catalog.update("UPDATE ux_core_control SET mode='PAUSED' WHERE id=1");
        try{recovery.activity(activity);assertEquals(1,engine.redis.remaining(activity));assertEquals("RELEASED",catalog.queryForObject("SELECT state FROM ux_core_stock_hold WHERE request_id=?",String.class,request.id()));assertThrows(Problem.class,()->purchase(user,activity));}
        finally{catalog.update("UPDATE ux_core_control SET mode='CORE' WHERE id=1");}
        actions(user);actions(user);assertEquals(1,engine.redis.remaining(activity));assertEquals(0L,engine.stock.invariants().get("stockViolations"));
    }
    @Test void mysqlRemainsTheFinalStockArbiterAcrossUserShards()throws Exception{
        long activity=activity(10);redis.opsForValue().set("cx:reserve:{"+activity+"}:stock","100");var requests=new ArrayList<CoreEngine.Request>();
        for(int i=0;i<30;i++)requests.add(purchase(user(),activity));var pool=Executors.newFixedThreadPool(12);
        try{var futures=requests.stream().map(r->pool.submit(()->engine.process(Long.parseLong(r.userId()),r.id(),activity))).toList();int succeeded=0,rejected=0;for(var f:futures){var r=f.get(30,TimeUnit.SECONDS);if(r.state().equals("SUCCEEDED"))succeeded++;if(r.state().equals("REJECTED"))rejected++;}assertEquals(10,succeeded);assertEquals(20,rejected);}finally{pool.shutdownNow();}
        for(var r:requests)actions(Long.parseLong(r.userId()));assertEquals(0,catalog.queryForObject("SELECT available FROM ux_activity WHERE id=?",Integer.class,activity));assertEquals(0L,engine.stock.invariants().get("stockViolations"));
    }
    @AfterAll void cleanup()throws Exception{
        if(store!=null){for(long user:users){int shard=CoreRouteAlgorithm.shard(user);for(String table:CoreStore.USER_TABLES)store.physical(shard).update("DELETE FROM "+CoreStore.table(table,shard)+" WHERE user_id=?",user);for(int n=0;n<32;n++)store.physical(n).update("DELETE FROM "+CoreStore.table("cx_check_log",n)+" WHERE user_id=?",user);
                catalog.update("DELETE r FROM ux_core_provider_refund r JOIN ux_core_channel_claim c ON r.channel_id=c.channel_id WHERE c.user_id=?",user);catalog.update("DELETE FROM ux_core_channel_claim WHERE user_id=?",user);catalog.update("DELETE FROM ux_core_order_route WHERE user_id=?",user);catalog.update("DELETE FROM ux_core_stock_hold WHERE user_id=?",user);catalog.update("DELETE FROM ux_account WHERE id=?",user);catalog.update("DELETE FROM ux_core_copy_user WHERE user_id=?",user);}
            for(long activity:activities){catalog.update("DELETE FROM ux_core_reservation_epoch WHERE activity_id=?",activity);catalog.update("DELETE FROM ux_core_migration WHERE name=?","recovery_"+activity);catalog.update("DELETE FROM ux_activity WHERE id=?",activity);store.db().update("DELETE FROM cx_event WHERE activity_id=?",activity);redis.delete(List.of("cx:reserve:{"+activity+"}:stock","cx:reserve:{"+activity+"}:users","cx:reserve:{"+activity+"}:requests","cx:reserve:{"+activity+"}:pending","cx:reserve:{"+activity+"}:epoch"));}
            catalog.update("UPDATE ux_core_control SET mode='LEGACY' WHERE id=1");if(ids!=null)ids.close();store.close();}
        if(factory!=null)factory.destroy();if(source!=null)source.close();
    }
}
