package com.hmdp.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SHARDING_INTEGRATION",matches="true")
class ShardingEnvironmentTest {
    static String url(int port,String schema) {
        return "jdbc:mysql://127.0.0.1:"+port+"/"+schema+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false&connectTimeout=2000&socketTimeout=5000&rewriteBatchedStatements=true";
    }
    static DriverManagerDataSource source(int port,String schema) {
        return new DriverManagerDataSource(url(port,schema),"qupiao",System.getenv("SHARDING_DB_PASSWORD"));
    }
    @Test void isolatedInstancesAndSourceMigrationsAreReady() throws Exception {
        var source=source(33307,"qupiao_sharding_source");
        Flyway.configure().dataSource(source).load().migrate();
        var db=new JdbcTemplate(source);
        assertEquals(32,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name REGEXP '^ux_order_read_[0-9]{2}$'",Integer.class));
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE",Integer.class)>=11);
        var counts=new LinkedHashMap<String,Long>();
        for(String name:List.of("ux_activity","ux_request","ux_order","ux_order_projection","ux_shop"))
            counts.put(name,db.queryForObject("SELECT COUNT(*) FROM "+name,Long.class));
        var instances=new ArrayList<Map<String,Object>>();
        for(int port:new int[]{33307,33317,33318}) {
            var jdbc=new JdbcTemplate(source(port,port==33307?"qupiao_sharding_source":"qupiao_history"));
            var row=jdbc.queryForMap("SELECT VERSION() AS version,@@port AS port,@@server_uuid AS serverUuid");
            assertEquals(port,((Number)row.get("port")).intValue()); instances.add(row);
            if(port!=33307) {
                String ddl=new String(new org.springframework.core.io.ClassPathResource("db/history/table.sql").getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                int start=port==33317?0:16;
                for(int i=start;i<start+16;i++) jdbc.execute(ddl.replace("__TABLE__",String.format(java.util.Locale.ROOT,"ux_order_read_%02d",i)));
                assertEquals(16,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name REGEXP '^ux_order_read_[0-9]{2}$'",Integer.class));
            }
        }
        assertEquals(3,instances.stream().map(x->x.get("serverUuid")).distinct().count());
        var path=Path.of("../docs/verification/2026-10-27-sharding/environment.json");
        Files.createDirectories(path.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),Map.of(
            "instances",instances,"sourceCounts",counts,"existingOrderProjectionTables",32,
            "scope","Isolated MySQL instances; existing Windows service on 3306 untouched."));
    }
}
