package com.hmdp.upgrade.core32;

import com.zaxxer.hikari.*;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.broadcast.config.BroadcastRuleConfiguration;
import org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration;
import org.apache.shardingsphere.sharding.api.config.*;
import org.apache.shardingsphere.sharding.api.config.rule.*;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.audit.ShardingAuditStrategyConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;

/** Main-order store. The business JDBC object talks to logical tables, not string-built physical names. */
public final class CoreStore implements AutoCloseable {
    public static final List<String> USER_TABLES=List.of("cx_user","cx_request","cx_order","cx_outbox","cx_action","cx_payment","cx_receipt","cx_refund","cx_close");
    public static final List<String> ALL_TABLES=new ArrayList<>(USER_TABLES);
    static {ALL_TABLES.add("cx_check_log");}
    private final List<HikariDataSource> pools=new ArrayList<>();
    private final DataSource dataSource;private final JdbcTemplate jdbc;private final TransactionTemplate transaction;
    private final ThreadLocal<Long> activeUser=new ThreadLocal<>();
    public CoreStore(String[] urls,String username,String password,boolean initialize) throws Exception {
        if(urls.length!=4||new HashSet<>(Arrays.asList(urls)).size()!=4)throw new IllegalArgumentException("Four different core URLs required");
        try {
            Map<String,DataSource> sources=new LinkedHashMap<>();
            for(int i=0;i<4;i++){
                var c=new HikariConfig();c.setJdbcUrl(urls[i]);c.setUsername(username);c.setPassword(password);
                c.setMaximumPoolSize(6);c.setMinimumIdle(0);c.setConnectionTimeout(2000);c.setPoolName("core32-"+i);
                var pool=new HikariDataSource(c);pools.add(pool);sources.put("db"+i,pool);
            }
            if(initialize)initialize();
            upgradeCloseMessages();
            var rule=new ShardingRuleConfiguration();
            for(String table:ALL_TABLES){
                String key=table.equals("cx_check_log")?"order_id":"user_id";
                String nodes=java.util.stream.IntStream.range(0,32).mapToObj(n->"db"+n/8+"."+table+"_"+n%8).collect(java.util.stream.Collectors.joining(","));
                var t=new ShardingTableRuleConfiguration(table,nodes);
                String kind=key.equals("user_id")?"user":"order";
                t.setDatabaseShardingStrategy(new StandardShardingStrategyConfiguration(key,kind+"_database"));
                t.setTableShardingStrategy(new StandardShardingStrategyConfiguration(key,kind+"_table"));
                rule.getTables().add(t);
            }
            rule.getBindingTableGroups().add(new ShardingTableReferenceRuleConfiguration("core_user_family",String.join(",",USER_TABLES)));
            for(String key:List.of("user","order"))for(String route:List.of("database","table")){
                var p=new Properties();p.setProperty("strategy","STANDARD");p.setProperty("algorithmClassName",CoreRouteAlgorithm.class.getName());p.setProperty("key",key);p.setProperty("route",route);
                rule.getShardingAlgorithms().put(key+"_"+route,new AlgorithmConfiguration("CLASS_BASED",p));
            }
            rule.getAuditors().put("core_key_required",new AlgorithmConfiguration("DML_SHARDING_CONDITIONS",new Properties()));
            rule.setDefaultAuditStrategy(new ShardingAuditStrategyConfiguration(List.of("core_key_required"),false));
            var properties=new Properties();properties.setProperty("sql-show","false");
            dataSource=ShardingSphereDataSourceFactory.createDataSource(sources,List.of(rule,new BroadcastRuleConfiguration(List.of("cx_event"))),properties);
            jdbc=new JdbcTemplate(dataSource);jdbc.setQueryTimeout(3);
            transaction=new TransactionTemplate(new DataSourceTransactionManager(dataSource));transaction.setIsolationLevel(2);transaction.setTimeout(8);
        }catch(Exception failure){pools.forEach(HikariDataSource::close);throw failure;}
    }
    private static String resource(String file)throws Exception{
        try(var stream=CoreStore.class.getResourceAsStream(file)){if(stream==null)throw new IllegalArgumentException(file);return new String(stream.readAllBytes(),StandardCharsets.UTF_8);}
    }
    private void initialize()throws Exception{
        String template=resource("/db/core32/tables.sql"),broadcast=resource("/db/core32/broadcast.sql");
        for(var pool:pools){var loader=new ResourceDatabasePopulator();for(int n=0;n<8;n++)loader.addScript(new ByteArrayResource(template.replace("__N__","_"+n).getBytes(StandardCharsets.UTF_8)));loader.addScript(new ByteArrayResource(broadcast.getBytes(StandardCharsets.UTF_8)));loader.execute(pool);
            var check=new JdbcTemplate(pool);for(int n=0;n<8;n++)if(check.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name='copy_revision'",Integer.class,"cx_user_"+n)==0)new ResourceDatabasePopulator(new ByteArrayResource(resource("/db/core32/V2__copy_revision.sql").replace("__N__","_"+n).getBytes(StandardCharsets.UTF_8))).execute(pool);
        }
    }
    public JdbcTemplate db(){return jdbc;}
    /** Additive upgrade for existing 32 close tables; old tasks are automatically resent. */
    private void upgradeCloseMessages(){
        for(var pool:pools){var db=new JdbcTemplate(pool);for(int n=0;n<8;n++){
            String table="cx_close_"+n;
            if(db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name='message_sent_at'",Integer.class,table)==0){
                db.execute("ALTER TABLE "+table+" ADD COLUMN message_sent_at TIMESTAMP(3) NULL");
            }
            if(db.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name='cx_close_send'",Integer.class,table)==0)
                db.execute("CREATE INDEX cx_close_send ON "+table+"(done_at,message_sent_at,next_enqueue_at)");
        }}
    }
    public <T>T tx(long user,Supplier<T> action){
        CoreRouteAlgorithm.shard(user);Long previous=activeUser.get();
        if(previous!=null){if(previous!=user)throw new IllegalStateException("Cross-user local transaction refused");return action.get();}
        activeUser.set(user);try{return transaction.execute(s->action.get());}finally{activeUser.remove();}
    }
    public JdbcTemplate physical(int shard){if(shard<0||shard>=32)throw new IllegalArgumentException("shard");var db=new JdbcTemplate(pools.get(shard/8));db.setQueryTimeout(3);return db;}
    public static String table(String table,int shard){if(!ALL_TABLES.contains(table)||shard<0||shard>=32)throw new IllegalArgumentException("fixed maintenance table required");return table+"_"+shard%8;}
    public List<Map<String,Object>> ready(String table,int shard,String predicate,int limit){
        if(!Set.of("cx_outbox","cx_action","cx_request","cx_close","cx_refund","cx_waitlist").contains(table)||limit<1||limit>100)throw new IllegalArgumentException("bounded worker scan required");
        return physical(shard).queryForList("SELECT * FROM "+table(table,shard)+" WHERE "+predicate+" LIMIT ?",limit);
    }
    public Map<String,Object> topology(){
        List<Map<String,Object>> instances=new ArrayList<>();Set<String> uuids=new HashSet<>();long orders=0,misplaced=0;
        for(int i=0;i<4;i++){
            var db=physical(i*8);var item=db.queryForMap("SELECT VERSION() version,@@server_uuid uuid,@@port port");instances.add(item);uuids.add(item.get("uuid").toString());
            for(int t=0;t<8;t++){var row=db.queryForMap("SELECT COUNT(*) total,COALESCE(SUM(MOD(user_id,32)<>?),0) misplaced FROM cx_order_"+t,i*8+t);orders+=((Number)row.get("total")).longValue();misplaced+=((Number)row.get("misplaced")).longValue();}
        }
        return Map.of("instances",instances,"independentInstances",uuids.size(),"orders",orders,"misplaced",misplaced,"logicalShards",32,"tablesPerDatabase",8);
    }
    @Override public void close()throws Exception{try{if(dataSource instanceof AutoCloseable closeable)closeable.close();}finally{pools.forEach(HikariDataSource::close);}}
}
