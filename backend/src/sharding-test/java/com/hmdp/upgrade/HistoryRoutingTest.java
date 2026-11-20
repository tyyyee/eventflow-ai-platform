package com.hmdp.upgrade;

import com.hmdp.upgrade.sharding.ShardedHistoryReplica;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SHARDING_INTEGRATION",matches="true")
class HistoryRoutingTest {
    @Test void all32UsersReachExactlyOneExpectedPhysicalTable() throws Exception {
        new ShardingEnvironmentTest().isolatedInstancesAndSourceMigrationsAreReady();
        var urls=new String[]{ShardingEnvironmentTest.url(33317,"qupiao_history"),ShardingEnvironmentTest.url(33318,"qupiao_history")};
        var targets=new JdbcTemplate[]{new JdbcTemplate(ShardingEnvironmentTest.source(33317,"qupiao_history")),new JdbcTemplate(ShardingEnvironmentTest.source(33318,"qupiao_history"))};
        var ids=new ArrayList<String>();
        try(var replica=new ShardedHistoryReplica(urls,"qupiao",System.getenv("SHARDING_DB_PASSWORD"))) {
            for(long user=1;user<=32;user++) {
                String id=UUID.randomUUID().toString();ids.add(id);
                var row=new HashMap<String,Object>();row.put("id",id);row.put("user_id",user);row.put("request_id",id);
                row.put("activity_id",1L);row.put("price_cents",990);row.put("state","CONFIRMED");row.put("revision",1L);
                row.put("created_at",Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));row.put("confirm_until",Timestamp.from(Instant.parse("2026-01-01T01:00:00Z")));
                replica.apply(row);
                int shard=(int)Math.floorMod(user,32);
                assertEquals(1,targets[shard/16].queryForObject("SELECT COUNT(*) FROM "+HistoryRows.table(user)+" WHERE id=?",Integer.class,id));
                assertEquals(1,replica.jdbc().queryForObject("SELECT COUNT(*) FROM ux_order_history WHERE user_id=? AND id=?",Integer.class,user,id));
                assertEquals(0,replica.jdbc().queryForObject("SELECT COUNT(*) FROM ux_order_history WHERE user_id=? AND id=?",Integer.class,user+32,id));
            }
            var pageIds=new ArrayList<String>();
            for(int i=1;i<=65;i++) {
                String id=UUID.randomUUID().toString();ids.add(id);pageIds.add(id);
                var row=new HashMap<String,Object>();row.put("id",id);row.put("user_id",33L);row.put("request_id",id);
                row.put("activity_id",(long)i);row.put("price_cents",990);row.put("state","CONFIRMED");row.put("revision",1L);
                row.put("created_at",Timestamp.from(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i)));
                row.put("confirm_until",Timestamp.from(Instant.parse("2026-01-01T01:00:00Z")));replica.apply(row);
            }
            var history=new ExternalOrderHistory(org.mockito.Mockito.mock(JdbcTemplate.class),null,replica);
            assertEquals(65,history.count(33));
            assertEquals(20,history.list(33,1).size());assertEquals(20,history.list(33,2).size());
            assertEquals(5,history.list(33,4).size());assertTrue(history.list(33,5).isEmpty());
            assertEquals(pageIds.get(64),history.list(33,1).get(0).id());
            assertEquals(pageIds.get(44),history.list(33,2).get(0).id());
            assertEquals(1,history.count(1));
        } finally {
            if(!ids.isEmpty())for(int shard=0;shard<32;shard++)
                targets[shard/16].update("DELETE FROM ux_order_read_"+String.format(Locale.ROOT,"%02d",shard)+" WHERE id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",ids.toArray());
        }
    }
}
