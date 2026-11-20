package com.hmdp.upgrade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SHARDING_BENCH_CLEANUP",matches="true")
class HistoryBenchmarkCleanupTest {
    @Test void boundedCleanupResumesOnlyOwnedSyntheticBatches() throws Exception {
        try(var source=HistoryBenchmarkTest.pool(33307,2);var target0=HistoryBenchmarkTest.pool(33317,2);var target1=HistoryBenchmarkTest.pool(33318,2)) {
            var db=new JdbcTemplate(source);var targets=new JdbcTemplate[]{new JdbcTemplate(target0),new JdbcTemplate(target1)};
            int deleted=HistoryBenchmarkTest.recoverOwned(db,"ux_order_single");
            for(int shard=0;shard<32;shard++) {
                String table=String.format(Locale.ROOT,"ux_order_read_%02d",shard);
                deleted+=HistoryBenchmarkTest.recoverOwned(db,table);
                deleted+=HistoryBenchmarkTest.recoverOwned(targets[shard/16],table);
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
                assertEquals(0,targets[shard/16].queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
            }
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ux_order_single",Integer.class));
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("../docs/verification/2026-10-27-sharding/cleanup-recovery.json").toFile(),
                Map.of("passed",true,"rowsDeletedInRecovery",deleted,"remainingBenchmarkRows",0,"method","Owned synthetic batches only; maximum 2000 rows per delete."));
        }
    }
}
