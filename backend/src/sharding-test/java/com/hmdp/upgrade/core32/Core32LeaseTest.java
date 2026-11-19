package com.hmdp.upgrade.core32;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CORE32_INTEGRATION",matches="true")
class Core32LeaseTest {
    @Test void renewsBeforeSpringStartsAndDoesNotReuseARecentlyReleasedWorker()throws Exception{
        try(var source=CoreMain.source()){
            Flyway.configure().dataSource(source).load().migrate();var db=new JdbcTemplate(source);
            try(var first=new LeasedIds(db);var second=new LeasedIds(db)){
                assertNotEquals(first.worker(),second.worker());long before=first.nextId();
                // Reproduce metadata/startup taking longer than the original 30-second lease.
                Thread.sleep(32000);assertTrue(first.nextId()>before);
                assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ux_core_id_worker WHERE worker_id=? AND expires_at>CURRENT_TIMESTAMP(3)",Integer.class,first.worker()));
                int previousWorker=second.worker();second.close();
                try(var replacement=new LeasedIds(db)){assertNotEquals(previousWorker,replacement.worker());}
            }
        }
    }
}
