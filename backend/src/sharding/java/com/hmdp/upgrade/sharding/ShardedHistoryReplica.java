package com.hmdp.upgrade.sharding;

import com.hmdp.upgrade.HistoryReplica;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.*;

public final class ShardedHistoryReplica implements HistoryReplica {
    private final DataSource dataSource;
    private final List<HikariDataSource> pools=new ArrayList<>();
    private final JdbcTemplate jdbc;
    public ShardedHistoryReplica(String[] urls,String username,String password) throws SQLException {
        if(urls.length!=2) throw new IllegalArgumentException("Exactly two history databases are required");
        Map<String,DataSource> sources=new LinkedHashMap<>();
        try {
            for(int i=0;i<2;i++) {
                var config=new HikariConfig();config.setJdbcUrl(urls[i]);config.setUsername(username);config.setPassword(password);
                config.setMaximumPoolSize(4);config.setMinimumIdle(0);config.setConnectionTimeout(2000);config.setPoolName("history-"+i);
                var pool=new HikariDataSource(config);pools.add(pool);sources.put("history"+i,pool);
            }
            var rule=new ShardingRuleConfiguration();
            String nodes=java.util.stream.IntStream.range(0,32).mapToObj(i->"history"+i/16+".ux_order_read_"+String.format(Locale.ROOT,"%02d",i)).collect(java.util.stream.Collectors.joining(","));
            var table=new ShardingTableRuleConfiguration("ux_order_history",nodes);
            table.setDatabaseShardingStrategy(new StandardShardingStrategyConfiguration("user_id","history_database"));
            table.setTableShardingStrategy(new StandardShardingStrategyConfiguration("user_id","history_table"));
            rule.getTables().add(table);
            for(String kind:List.of("database","table")) {
                var props=new Properties();props.setProperty("strategy","STANDARD");
                props.setProperty("algorithmClassName",HistoryShardingAlgorithm.class.getName());props.setProperty("route",kind);
                rule.getShardingAlgorithms().put("history_"+kind,new AlgorithmConfiguration("CLASS_BASED",props));
            }
            var props=new Properties();props.setProperty("sql-show","false");
            dataSource=ShardingSphereDataSourceFactory.createDataSource(sources,List.of(rule),props);
            jdbc=new JdbcTemplate(dataSource);jdbc.setQueryTimeout(3);
        } catch(SQLException | RuntimeException failure) { pools.forEach(HikariDataSource::close);throw failure; }
    }
    @Override public JdbcTemplate jdbc() { return jdbc; }
    @Override public Map<String,Long> audit() {
        long rows=0,misplaced=0;
        for(int shard=0;shard<32;shard++) {
            var db=new JdbcTemplate(pools.get(shard/16));db.setQueryTimeout(3);
            var counts=db.queryForMap("SELECT COUNT(*) AS total,COALESCE(SUM(MOD(user_id,32)<>?),0) AS misplaced FROM ux_order_read_"+String.format(Locale.ROOT,"%02d",shard),shard);
            rows+=((Number)counts.get("total")).longValue();misplaced+=((Number)counts.get("misplaced")).longValue();
        }
        return Map.of("rows",rows,"misplaced",misplaced);
    }
    @Override public void close() throws Exception {
        try { if(dataSource instanceof AutoCloseable closeable) closeable.close(); }
        finally { pools.forEach(HikariDataSource::close); }
    }
}
