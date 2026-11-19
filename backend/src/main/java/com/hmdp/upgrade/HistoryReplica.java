package com.hmdp.upgrade;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;

/** Optional read-model resource. Never registered as the application's primary DataSource. */
public interface HistoryReplica extends AutoCloseable {
    JdbcTemplate jdbc();
    default void apply(Map<String,Object> row) { HistoryRows.apply(jdbc(),"ux_order_history",row); }
    default int physicalDatabases() { return 2; }
    Map<String,Long> audit();
    @Override default void close() throws Exception {}
}
