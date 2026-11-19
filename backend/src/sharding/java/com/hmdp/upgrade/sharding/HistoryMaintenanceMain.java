package com.hmdp.upgrade.sharding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.upgrade.*;
import com.zaxxer.hikari.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.*;

/** Local maintenance with explicit DB credentials; does not enable benchmark HTTP authentication. */
public final class HistoryMaintenanceMain {
    private static String env(String key,String fallback) {String value=System.getenv(key);return value==null||value.isBlank()?fallback:value;}
    public static void main(String[] args) throws Exception {
        try { run(args); }
        finally {
            // exec:java shares Maven's JVM; finish the driver's own cleanup thread as well.
            try {Class.forName("com.mysql.cj.jdbc.AbandonedConnectionCleanupThread").getMethod("checkedShutdown").invoke(null);}
            catch(ReflectiveOperationException ignored) {}
        }
    }
    private static void run(String[] args) throws Exception {
        String action=args.length==0?"status":args[0];
        if(!Set.of("status","sync","backfill","verify","local","sharded").contains(action))throw new IllegalArgumentException("Unknown history maintenance action");
        String password=env("SHARDING_DB_PASSWORD",null);if(password==null)throw new IllegalStateException("SHARDING_DB_PASSWORD is required");
        String suffix="?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false&connectTimeout=2000&socketTimeout=5000";
        var config=new HikariConfig();config.setJdbcUrl(env("HISTORY_SOURCE_URL","jdbc:mysql://127.0.0.1:33307/qupiao_sharding_source"+suffix));
        config.setUsername(env("HISTORY_SOURCE_USER","qupiao"));config.setPassword(env("HISTORY_SOURCE_PASSWORD",password));config.setMaximumPoolSize(4);config.setMinimumIdle(0);
        String[] urls=env("HISTORY_URLS","jdbc:mysql://127.0.0.1:33317/qupiao_history"+suffix+",jdbc:mysql://127.0.0.1:33318/qupiao_history"+suffix).split(",");
        try(var source=new HikariDataSource(config)) {
            var db=new JdbcTemplate(source);
            var transactions=new Transactions(new DataSourceTransactionManager(source));
            // Rollback must remain available when a query node cannot even initialize.
            if(action.equals("local")) {
                new ExternalOrderHistory(db,transactions,(HistoryReplica)null).readMode("LOCAL",0);
                System.out.println("{\"action\":\"local\",\"readMode\":\"LOCAL\",\"targetConnectionRequired\":false}");
                return;
            }
            try(var target=new ShardedHistoryReplica(urls,env("HISTORY_DB_USER","qupiao"),env("HISTORY_DB_PASSWORD",password))) {
            var history=new ExternalOrderHistory(db,transactions,target);
            Map<String,Object> result=Map.of();
            long deadline=System.nanoTime()+30_000_000_000L;
            if(action.equals("local")||action.equals("sharded"))history.readMode(action.toUpperCase(Locale.ROOT),0);
            else if(action.equals("sync")) {
                do {int done=history.drain(100);result=Map.of("applied",done,"pending",history.pending());if(done==0)break;}
                while(history.pending()>0 && System.nanoTime()<deadline);
            } else if(action.equals("backfill")||action.equals("verify")) {
                do {result=action.equals("backfill")?history.backfill(100):history.verify(100);}
                while(!Boolean.TRUE.equals(result.get("done")) && System.nanoTime()<deadline);
            }
            System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("action",action,"result",result,"status",history.status())));
            }
        }
    }
}
