package com.hmdp.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real RocketMQ (including timer/retry service), isolated SQL database, no scheduled fallback. */
@EnabledIfEnvironmentVariable(named="ROCKETMQ_INTEGRATION",matches="true")
class RocketOrderFlowTest {
    JdbcTemplate db;Transactions tx;Trading trading;Payments payments;RocketMessageBus bus;DelayedClose closer;
    String orders,closes;ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup(){
        String suffix=UUID.randomUUID().toString().replace("-","");orders="verify-orders-"+suffix;closes="verify-close-"+suffix;
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+suffix+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        var loader=new ResourceDatabasePopulator();for(String version:new String[]{"V1__upgrade.sql","V3__reservation_actions.sql","V11__payment_and_close_tasks.sql","V16__rocketmq_delivery.sql"})loader.addScript(new ClassPathResource("db/migration/"+version));loader.execute(source);
        db=new JdbcTemplate(source);tx=new Transactions(new DataSourceTransactionManager(source));trading=new Trading(db,tx);
        ReflectionTestUtils.setField(trading,"closeTasks",new CloseTasks(db));payments=new Payments(db,tx,trading);
        var now=Instant.now();db.update("INSERT INTO ux_activity VALUES(1,10,10,990,?,?,?)",Timestamp.from(now.minusSeconds(60)),Timestamp.from(now.plusSeconds(600)),Timestamp.from(now.plusSeconds(900)));
        bus=new RocketMessageBus(System.getenv().getOrDefault("ROCKETMQ_NAMESERVER","127.0.0.1:39876"),"verify-producer-"+suffix,true);
        closer=new DelayedClose(db,trading,bus);ReflectionTestUtils.setField(closer,"topic",closes);
    }
    @AfterEach void stop(){if(bus!=null)bus.stop();}
    String order(long user){return trading.accept(user,"rocket_request_"+user,1,true).id();}
    Instant due(String request,int seconds){var due=Instant.now().plusSeconds(seconds);db.update("UPDATE ux_order SET confirm_until=? WHERE request_id=?",Timestamp.from(due),request);db.update("UPDATE ux_close_task SET due_at=? WHERE request_id=?",Timestamp.from(due),request);return due;}
    int count(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    @Test void brokerRetriesBeforeAndAfterCommitWithoutDuplicateOrder()throws Exception{
        var consumer=new Delivery(db,tx,trading,bus,json,mock(Admission.class),mock(Reservations.class),orders,true);
        var attempts=new AtomicInteger();var completed=new CountDownLatch(1);
        bus.subscribe(orders,orders,record->{int attempt=attempts.incrementAndGet();if(attempt==1)throw new IllegalStateException("database temporarily unavailable");consumer.consume(record);if(attempt==2)throw new IllegalStateException("process lost acknowledgement after commit");completed.countDown();});
        bus.start();var request=trading.accept(1,"async_rocket_request",1,false);assertEquals("ACCEPTED",request.state());
        consumer.relay();assertTrue(completed.await(90,TimeUnit.SECONDS),()->"Broker must redeliver; attempts="+attempts.get()+", retries="+bus.retries.sum());
        assertEquals(1,count("ux_order"));assertEquals("SUCCEEDED",trading.result(1,request.id()).state());assertTrue(attempts.get()>=3);
        assertNotNull(db.queryForMap("SELECT sent_at FROM ux_outbox WHERE request_id=?",request.id()).get("sent_at"));
        assertEquals(0L,trading.invariants().get("stockViolations"));
    }
    @Test void realDelayedMessagesCloseOnceAndLatePaymentRefundsOnce()throws Exception{
        var received=new CountDownLatch(2);bus.subscribe(closes,closes,record->{closer.consume(record);received.countDown();});bus.start();
        String r=order(1),payment=(String)payments.create(1,r).get("id");Instant deadline=due(r,6);
        closer.enqueue();bus.sendAt(closes,r,json.writeValueAsString(new DelayedClose.Event(1,1,r)),deadline).get(10,TimeUnit.SECONDS);
        assertEquals("PENDING_CONFIRM",trading.order(1,r).get("state"));assertFalse(received.await(1,TimeUnit.SECONDS));
        assertTrue(received.await(25,TimeUnit.SECONDS),"Must close through MQ, without calling reconcile");
        assertFalse(Instant.now().isBefore(deadline));assertEquals("EXPIRED",trading.order(1,r).get("state"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ux_reservation_action WHERE action='RESTORE'",Integer.class));
        payments.received(1,payment,"late_rocket_payment",990);payments.received(1,payment,"late_rocket_payment",990);payments.refunds();payments.refunds();
        assertEquals(1,count("ux_sandbox_refund"));assertEquals("REFUNDED",payments.get(1,payment).get("state"));assertEquals(0L,trading.invariants().get("stockViolations"));
    }
    @Test void delayedMessageDoesNotCloseAnAlreadyPaidOrder()throws Exception{
        var received=new CountDownLatch(1);bus.subscribe(closes,closes,record->{closer.consume(record);received.countDown();});bus.start();
        String r=order(1),payment=(String)payments.create(1,r).get("id");due(r,5);closer.enqueue();
        payments.received(1,payment,"paid_rocket_payment",990);
        assertTrue(received.await(25,TimeUnit.SECONDS));assertEquals("CONFIRMED",trading.order(1,r).get("state"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ux_reservation_action WHERE action='RESTORE'",Integer.class));assertEquals(0,count("ux_refund"));
    }
}
