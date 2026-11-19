package com.hmdp.upgrade.core32;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;
import com.zaxxer.hikari.HikariDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CORE32_INTEGRATION",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Core32StoreTest {
    CoreStore store;HikariDataSource source;final List<String> owned=new ArrayList<>();
    @BeforeAll void setup()throws Exception{source=CoreMain.source();Flyway.configure().dataSource(source).load().migrate();store=new CoreStore(CoreMain.urls(),"qupiao",System.getenv("CORE32_PASSWORD"),true);for(int n=0;n<32;n++)store.physical(n).update("DELETE FROM cx_user_"+n%8+" WHERE user_id=? AND username=? AND NOT EXISTS(SELECT 1 FROM cx_order_"+n%8+" o WHERE o.user_id=?)",8_000_000+n,"routing_"+(8_000_000+n),8_000_000+n);}
    @AfterAll void close()throws Exception{if(store!=null){for(int shard=0;shard<32;shard++){for(String id:owned){store.physical(shard).update("DELETE FROM cx_order_"+shard%8+" WHERE id=?",id);store.physical(shard).update("DELETE FROM cx_request_"+shard%8+" WHERE id=?",id);}store.physical(shard).update("DELETE FROM cx_user_"+shard%8+" WHERE user_id=? AND username=?",8_000_000+shard,"routing_"+(8_000_000+shard));}store.close();}if(source!=null)source.close();}
    @Test void routesAllThirtyTwoShardsAndBindingJoin()throws Exception{
        var db=store.db();
        for(int n=0;n<32;n++){
            long user=8_000_000+n;String id=UUID.randomUUID().toString();owned.add(id);
            store.tx(user,()->{db.update("INSERT IGNORE INTO cx_user(user_id,username,display_name,created_at) VALUES(?,?,?,CURRENT_TIMESTAMP(3))",user,"routing_"+user,"Routing test");
                db.update("INSERT INTO cx_request(id,user_id,request_key,activity_id,order_id,state,created_at,deadline) VALUES(?,?,?,?,?,'SUCCEEDED',CURRENT_TIMESTAMP(3),TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(3)))",id,user,id,1000+nValue(user),id);
                db.update("INSERT INTO cx_order(id,user_id,request_id,activity_id,price_cents,state,confirm_until,created_at) VALUES(?,?,?,?,990,'CONFIRMED',TIMESTAMPADD(SECOND,300,CURRENT_TIMESTAMP(3)),CURRENT_TIMESTAMP(3))",id,user,id,1000+nValue(user));return null;});
            int shard=CoreRouteAlgorithm.shard(user);
            assertEquals(1,store.physical(shard).queryForObject("SELECT COUNT(*) FROM cx_order_"+shard%8+" WHERE id=? AND user_id=?",Integer.class,id,user));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM cx_user u JOIN cx_order o ON u.user_id=o.user_id WHERE u.user_id=? AND o.id=?",Integer.class,user,id));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM cx_order WHERE user_id=? AND id=?",Integer.class,user+32,id));
        }
        assertEquals(4,store.topology().get("independentInstances"));assertEquals(0L,store.topology().get("misplaced"));
        assertThrows(Exception.class,()->db.queryForObject("SELECT COUNT(*) FROM cx_order",Long.class));
    }
    private int nValue(long user){return (int)(user%32);}
    @Test void broadcastContainsConfigurationInAllFourInstances(){
        store.db().update("INSERT INTO cx_event(activity_id,shop_name,price_cents,starts_at,ends_at) VALUES(999999,'Broadcast test',990,CURRENT_TIMESTAMP(3),TIMESTAMPADD(SECOND,3600,CURRENT_TIMESTAMP(3))) ON DUPLICATE KEY UPDATE shop_name=VALUES(shop_name)");
        for(int n=0;n<4;n++)assertEquals("Broadcast test",store.physical(n*8).queryForObject("SELECT shop_name FROM cx_event WHERE activity_id=999999",String.class));
        store.db().update("DELETE FROM cx_event WHERE activity_id=999999");
    }
    @Test void oneUserTransactionRollsBackItsWholeOrderFamily(){
        long user=8_000_099;String id=UUID.randomUUID().toString();owned.add(id);
        assertThrows(IllegalStateException.class,()->store.tx(user,()->{store.db().update("INSERT INTO cx_request(id,user_id,request_key,activity_id,order_id,state,created_at,deadline) VALUES(?,?,?,?,?,'ACCEPTED',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))",id,user,id,1000,id);throw new IllegalStateException("process died before local commit");}));
        assertEquals(0,store.db().queryForObject("SELECT COUNT(*) FROM cx_request WHERE user_id=? AND id=?",Integer.class,user,id));
    }
}
