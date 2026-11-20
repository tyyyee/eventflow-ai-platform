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
import org.springframework.test.util.ReflectionTestUtils;
import com.zaxxer.hikari.HikariDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CORE32_INTEGRATION",matches="true")
class Core32MigrationTest {
    @Test void oldTransactionsJournaledCopyRestartCutoverAndReverseMigration()throws Exception{
        try(var source=CoreMain.source()){
            Flyway.configure().dataSource(source).load().migrate();var catalog=new JdbcTemplate(source);var tx=new Transactions(new DataSourceTransactionManager(source));
            try(var store=new CoreStore(CoreMain.urls(),"qupiao",System.getenv("CORE32_PASSWORD"),true);var ids=new LeasedIds(catalog)){
                var configuration=new RedisStandaloneConfiguration("127.0.0.1",36380);configuration.setDatabase(12);configuration.setPassword(System.getenv("CORE32_REDIS_PASSWORD"));var factory=new LettuceConnectionFactory(configuration);factory.afterPropertiesSet();factory.start();var redis=new StringRedisTemplate(factory);
                var engine=new CoreEngine(store,new CoreStock(catalog,tx),new CoreRedis(redis),ids,tx);long user=System.currentTimeMillis()*100+88;long activity=user+1;String username="cm_"+user;var ownedActivities=new ArrayList<Long>();
                try{
                    catalog.update("UPDATE ux_core_control SET mode='LEGACY' WHERE id=1");catalog.update("INSERT INTO ux_account(id,username,display_name,password_hash,created_at) VALUES(?,?,?,'migration-test-only',CURRENT_TIMESTAMP(3))",user,username,"Migration test");
                    var legacy=new Trading(catalog,tx);ReflectionTestUtils.setField(legacy,"coreModeGate",new CoreModeGate(catalog));
                    var close=org.springframework.beans.BeanUtils.instantiateClass(CloseTasks.class.getDeclaredConstructor(JdbcTemplate.class),catalog);ReflectionTestUtils.setField(legacy,"closeTasks",close);
                    for(int n=0;n<4;n++){long id=activity+n;ownedActivities.add(id);catalog.update("INSERT INTO ux_activity(id,capacity,available,price_cents,starts_at,ends_at,process_until) VALUES(?,2,2,990,TIMESTAMPADD(SECOND,-60,CURRENT_TIMESTAMP(3)),TIMESTAMPADD(HOUR,2,CURRENT_TIMESTAMP(3)),TIMESTAMPADD(HOUR,3,CURRENT_TIMESTAMP(3)))",id);}
                    var old=legacy.accept(user,"old_"+UUID.randomUUID(),activity,true);var migration=new CoreMigration(engine);migration.begin();assertTrue(migration.pending()>0);migration.drain(10);assertEquals("PENDING_CONFIRM",engine.order(user,old.id()).get("state"));
                    legacy.transition(user,old.id(),"cancel");var during=legacy.accept(user,"during_"+UUID.randomUUID(),activity+1,true);assertTrue(migration.pending()>0);
                    var restarted=new CoreMigration(engine);restarted.drain(10);assertEquals("CANCELLED",engine.order(user,old.id()).get("state"));var verification=restarted.verify();assertTrue((Boolean)verification.get("passed"),verification.toString());
                    ids.renew();restarted.activate();assertEquals("CORE",catalog.queryForObject("SELECT mode FROM ux_core_control WHERE id=1",String.class));assertThrows(Problem.class,()->legacy.accept(user,"forbidden_"+UUID.randomUUID(),activity+2,true));
                    var fresh=engine.purchase(user,"fresh_"+UUID.randomUUID(),activity+2);engine.process(user,fresh.id(),activity+2);engine.transition(user,fresh.id(),"cancel");
                    assertEquals(2,catalog.queryForObject("SELECT available FROM ux_activity WHERE id=?",Integer.class,activity));assertEquals(1,catalog.queryForObject("SELECT available FROM ux_activity WHERE id=?",Integer.class,activity+1));
                    catalog.update("UPDATE ux_close_task SET message_sent_at=CURRENT_TIMESTAMP(3) WHERE user_id=?",user);
                    catalog.update("UPDATE ux_core_control SET mode='PAUSED' WHERE id=1");ids.renew();var rollback=restarted.rollback(redis,tx);assertEquals("LEGACY",rollback.get("mode"));
                    assertNull(catalog.queryForMap("SELECT message_sent_at FROM ux_close_task WHERE request_id=?",during.id()).get("message_sent_at"));
                    assertEquals("CANCELLED",legacy.order(user,fresh.id()).get("state"));assertEquals("PENDING_CONFIRM",legacy.order(user,during.id()).get("state"));assertEquals(0L,legacy.invariants().get("stockViolations"));assertEquals(0L,legacy.invariants().get("stateViolations"));
                    assertEquals("SUCCEEDED",legacy.accept(user,"after_"+UUID.randomUUID(),activity+3,true).state());
                }finally{
                    int shard=CoreRouteAlgorithm.shard(user);for(String table:CoreStore.USER_TABLES)store.physical(shard).update("DELETE FROM "+CoreStore.table(table,shard)+" WHERE user_id=?",user);
                    for(int n=0;n<32;n++)store.physical(n).update("DELETE FROM cx_check_log_"+n%8+" WHERE user_id=?",user);
                    catalog.update("DELETE p FROM ux_order_projection p JOIN ux_order o ON o.id=p.order_id WHERE o.user_id=?",user);
                    catalog.update("DELETE a FROM ux_reservation_action a JOIN ux_request r ON r.id=a.request_id WHERE r.user_id=?",user);
                    catalog.update("DELETE FROM ux_close_task WHERE user_id=?",user);catalog.update("DELETE b FROM ux_outbox b JOIN ux_request r ON r.id=b.request_id WHERE r.user_id=?",user);catalog.update("DELETE FROM ux_order WHERE user_id=?",user);catalog.update("DELETE FROM ux_request WHERE user_id=?",user);
                    catalog.update("DELETE FROM ux_core_stock_hold WHERE user_id=?",user);catalog.update("DELETE FROM ux_core_order_route WHERE user_id=?",user);catalog.update("DELETE FROM ux_account WHERE id=?",user);catalog.update("DELETE FROM ux_core_copy_user WHERE user_id=?",user);
                    for(long id:ownedActivities){catalog.update("DELETE FROM ux_core_reservation_epoch WHERE activity_id=?",id);catalog.update("DELETE FROM ux_reservation_epoch WHERE activity_id=?",id);catalog.update("DELETE FROM ux_activity WHERE id=?",id);var keys=redis.keys("*reserve:{"+id+"}:*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}
                    catalog.update("UPDATE ux_core_control SET mode='LEGACY' WHERE id=1");catalog.update("DELETE FROM ux_core_migration WHERE name IN ('verified','activated','recovery_all')");factory.destroy();
                }
            }
        }
    }
}
