package com.hmdp.upgrade.core32;

import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import com.hmdp.upgrade.Transactions;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class CoreMain {
    static String environment(String name,String fallback){String value=System.getenv(name);return value==null||value.isBlank()?fallback:value;}
    public static HikariDataSource source(){var c=new HikariConfig();c.setJdbcUrl(environment("CORE32_SOURCE_URL","jdbc:mysql://127.0.0.1:33307/qupiao_core_catalog?connectionTimeZone=UTC&socketTimeout=5000"));c.setUsername("qupiao");c.setPassword(environment("CORE32_PASSWORD",""));c.setMaximumPoolSize(4);c.setMinimumIdle(0);return new HikariDataSource(c);}
    public static String[] urls(){return environment("CORE32_URLS",java.util.stream.IntStream.range(0,4).mapToObj(n->"jdbc:mysql://127.0.0.1:"+(33400+n)+"/qupiao_core32?connectionTimeZone=UTC&socketTimeout=5000").collect(java.util.stream.Collectors.joining(","))).split(",");}
    public static void main(String[] args)throws Exception{
        String action=args.length==0?"status":args[0];
        try(var source=source()){
            Flyway.configure().dataSource(source).load().migrate();
            try(var store=new CoreStore(urls(),"qupiao",environment("CORE32_PASSWORD",""),action.equals("init"))){
                var result=new LinkedHashMap<String,Object>();result.put("action",action);result.put("topology",store.topology());
                if(!Set.of("init","status").contains(action)){
                    var redisConfiguration=new RedisStandaloneConfiguration("127.0.0.1",36380);redisConfiguration.setPassword(environment("CORE32_REDIS_PASSWORD",""));redisConfiguration.setDatabase(Integer.parseInt(environment("CORE32_REDIS_DATABASE","11")));
                    var factory=new LettuceConnectionFactory(redisConfiguration);factory.afterPropertiesSet();factory.start();
                    try(var ids=new LeasedIds(new JdbcTemplate(source))){var jdbc=new JdbcTemplate(source);var tx=new Transactions(new DataSourceTransactionManager(source));var redis=new StringRedisTemplate(factory);var engine=new CoreEngine(store,new CoreStock(jdbc,tx),new CoreRedis(redis),ids,tx);var migration=new CoreMigration(engine);
                        switch(action){
                            case "begin"->migration.begin();
                            case "copy"->{long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25);int copied=0;while(System.nanoTime()<deadline){ids.renew();int batch=migration.drain(10);copied+=batch;if(batch==0)break;}result.put("copiedUsers",copied);result.put("pending",migration.pending());}
                            case "verify"->result.put("verification",migration.verify());
                            case "activate"->{migration.activate();result.put("mode","CORE");}
                            case "pause"->{jdbc.update("UPDATE ux_core_control SET mode='PAUSED',changed_at=CURRENT_TIMESTAMP(3) WHERE id=1");result.put("mode","PAUSED");}
                            case "recover"->result.put("activities",new CoreRecovery(engine).all());
                            case "rollback"->result.put("rollback",migration.rollback(redis,tx));
                            default->throw new IllegalArgumentException("Unknown core maintenance action");
                        }
                    }finally{factory.destroy();}
                }
                result.put("control",new JdbcTemplate(source).queryForMap("SELECT * FROM ux_core_control WHERE id=1"));
                System.out.println(new ObjectMapper().writeValueAsString(result));
            }
        }finally{try{Class.forName("com.mysql.cj.jdbc.AbandonedConnectionCleanupThread").getMethod("checkedShutdown").invoke(null);}catch(ReflectiveOperationException ignored){}}
    }
}
