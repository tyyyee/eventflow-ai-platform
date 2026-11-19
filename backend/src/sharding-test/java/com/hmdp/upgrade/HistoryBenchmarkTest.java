package com.hmdp.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.upgrade.sharding.ShardedHistoryReplica;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** SQL/JDBC read-path comparison, not HTTP throughput, seckill TPS or a production capacity claim. */
@EnabledIfEnvironmentVariable(named="SHARDING_BENCH",matches="true")
class HistoryBenchmarkTest {
    static final String SCHEMA="qupiao_history_bench";
    static final int CONCURRENCY=8,SECONDS=3,ROUNDS=3;
    static final String INSERT="(id,user_id,request_id,activity_id,price_cents,state,created_at,confirm_until,revision,shop_id,shop_name,offer_title,image_path) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)";
    static HikariDataSource pool(int port,int maximum) {
        var c=new HikariConfig();c.setJdbcUrl(ShardingEnvironmentTest.url(port,SCHEMA));c.setUsername("qupiao");c.setPassword(System.getenv("SHARDING_DB_PASSWORD"));
        c.setMaximumPoolSize(maximum);c.setMinimumIdle(0);c.setConnectionTimeout(2000);return new HikariDataSource(c);
    }
    static long mix(long value) {value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;value=(value^(value>>>27))*0x94d049bb133111ebL;return value^(value>>>31);}
    static long user(long i) {return i%10==0?7:10000+Math.floorMod(mix(i^20261027L),10000);}
    static String state(long i) {return List.of("PENDING_CONFIRM","CONFIRMED","CANCELLED","EXPIRED").get((int)(i%4));}
    record View(JdbcTemplate db,String fixedTable) {
        String table(long user) {return fixedTable==null?HistoryRows.table(user):fixedTable;}
        List<OrderProjection.HistoryItem> list(long user) {return db.query("SELECT "+HistoryRows.COLUMNS+" FROM "+table(user)+" WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 20",HistoryRows::item,user);}
        int count(long user) {return db.queryForObject("SELECT COUNT(*) FROM "+table(user)+" WHERE user_id=?",Integer.class,user);}
    }
    @Test void compare100kAndOneMillionSyntheticHistories() throws Exception {
        String ddl=new String(new ClassPathResource("db/history/table.sql").getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        try(var source=pool(33307,8)) {
            var db=new JdbcTemplate(source);
            db.execute(ddl.replace("__TABLE__","ux_order_single"));
            for(int shard=0;shard<32;shard++) {
                String table=String.format(Locale.ROOT,"ux_order_read_%02d",shard);
                db.execute(ddl.replace("__TABLE__",table));
                new JdbcTemplate(ShardingEnvironmentTest.source(shard<16?33317:33318,SCHEMA)).execute(ddl.replace("__TABLE__",table));
            }
            try(var replica=new ShardedHistoryReplica(new String[]{ShardingEnvironmentTest.url(33317,SCHEMA),ShardingEnvironmentTest.url(33318,SCHEMA)},"qupiao",System.getenv("SHARDING_DB_PASSWORD"))) {
                var views=Map.of("single",new View(db,"ux_order_single"),"same-instance-32",new View(db,null),"two-instance-32",new View(replica.jdbc(),"ux_order_history"));
                var results=new ArrayList<Map<String,Object>>();
                var plans=new ArrayList<Map<String,Object>>();
                var mapper=new ObjectMapper();
                Path evidence=Path.of("../docs/verification/2026-10-27-sharding/benchmark.json");
                for(int size:new int[]{100000,1000000}) {
                    String batch=UUID.randomUUID().toString().substring(0,8);
                    var counts=new int[20000];
                    try {
                        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ux_order_single",Integer.class),"Benchmark schema must contain no previous fixtures.");
                        try(var conn0=ShardingEnvironmentTest.source(33317,SCHEMA).getConnection();var conn1=ShardingEnvironmentTest.source(33318,SCHEMA).getConnection()) {
                            var loads=new JdbcTemplate[]{new JdbcTemplate(new SingleConnectionDataSource(conn0,true)),new JdbcTemplate(new SingleConnectionDataSource(conn1,true))};
                            seed(db,loads,size,batch,counts);
                        }
                        assertEquals(size,db.queryForObject("SELECT COUNT(*) FROM ux_order_single",Integer.class));
                        assertEquals((long)size,replica.audit().get("rows"));assertEquals(0L,replica.audit().get("misplaced"));
                        long localCount=0;
                        for(int i=0;i<32;i++)localCount+=db.queryForObject("SELECT COUNT(*) FROM ux_order_read_"+String.format(Locale.ROOT,"%02d",i),Long.class);
                        assertEquals(size,localCount);
                        int[] normal=java.util.stream.IntStream.range(10000,20000).filter(i->counts[i]>0).toArray();
                        for(var entry:views.entrySet()) for(long account:new long[]{7,normal[0],normal[normal.length/2]})validate(entry.getValue(),account,counts,batch);
                        for(var entry:views.entrySet()) {
                            long account=normal[0];
                            var explainDb=entry.getValue().db();
                            String explainTable=entry.getValue().table(account);
                            if(entry.getKey().equals("two-instance-32")) {
                                explainDb=new JdbcTemplate(ShardingEnvironmentTest.source(Math.floorMod(account,32)<16?33317:33318,SCHEMA));
                                explainTable=HistoryRows.table(account);
                            }
                            plans.add(Map.of("rows",size,"mode",entry.getKey(),"explain",explainDb.queryForList("EXPLAIN SELECT "+HistoryRows.COLUMNS+" FROM "+explainTable+" WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 20",account)));
                        }
                        for(String workload:List.of("normal","mixed-10pct-heavy")) for(int round=0;round<ROUNDS;round++) {
                            var modes=new ArrayList<>(List.of("single","same-instance-32","two-instance-32"));
                            Collections.rotate(modes,round);
                            for(String mode:modes) {
                                // Warm every mode equally, outside the timed sample.
                                for(int i=0;i<200;i++)validate(views.get(mode),normal[i%normal.length],counts,batch);
                                var result=measure(views.get(mode),normal,counts,batch,workload);
                                @SuppressWarnings("unchecked") var raw=(List<Long>)result.remove("_samples");
                                Path samples=Path.of("../.local/sharding-benchmark/"+size+"-"+mode+"-"+workload+"-"+(round+1)+".csv");
                                Files.createDirectories(samples.getParent());
                                try(var writer=Files.newBufferedWriter(samples)) {writer.write("latencyNanos\n");for(long value:raw){writer.write(Long.toString(value));writer.newLine();}}
                                result.put("sampleFile",samples.toString());
                                result.put("rows",size);result.put("mode",mode);result.put("round",round+1);results.add(result);
                                mapper.writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(),Map.of(
                                    "scope","SQL/JDBC list+count, closed-loop local read benchmark. No HTTP or normal session authentication cost.",
                                    "data","Deterministic synthetic histories, seed 20261027; 10% of orders belong to user 7.",
                                    "concurrency",CONCURRENCY,"secondsPerRound",SECONDS,"connectionBudgetPerMode",8,
                                    "bufferPoolBudgetBytesPerMode",134217728,
                                    "limitations",List.of("All processes share one host and disk.","Target pools have four connections per instance; a single hot user is routed to one instance.","Warm-cache sequential runs with rotated mode order; not maximum capacity or an isolated sharding-factor experiment.","Client processing and ShardingSphere overhead are included; background seckill and replication jobs are not running."),
                                    "rounds",results,"queryPlans",plans));
                                System.out.println("BENCH rows="+size+" mode="+mode+" workload="+workload+" round="+(round+1)+" completed="+result.get("completed")+" failures="+result.get("failures"));
                                assertEquals(0,((Number)result.get("failures")).intValue());
                            }
                        }
                    } finally {
                        deleteBatch(db,"ux_order_single",batch);
                        for(int shard=0;shard<32;shard++) {
                            String table=String.format(Locale.ROOT,"ux_order_read_%02d",shard);
                            deleteBatch(db,table,batch);
                            deleteBatch(new JdbcTemplate(ShardingEnvironmentTest.source(shard<16?33317:33318,SCHEMA)),table,batch);
                        }
                    }
                }
            }
        }
    }
    static int deleteBatch(JdbcTemplate db,String table,String batch) {
        if(!table.matches("ux_order_single|ux_order_read_[0-9]{2}") || !batch.matches("[a-f0-9]{8}"))throw new IllegalArgumentException("Invalid benchmark ownership");
        assertEquals(SCHEMA,db.queryForObject("SELECT DATABASE()",String.class));
        int total=0;
        for(int i=0;i<1000;i++) {
            int changed=db.update("DELETE FROM "+table+" WHERE id LIKE ? LIMIT 2000",batch+"-%");
            total+=changed;if(changed==0)return total;
        }
        throw new IllegalStateException("Cleanup budget exhausted; owned batch remains resumable");
    }
    static int recoverOwned(JdbcTemplate db,String table) {
        int total=0;
        for(int i=0;i<10;i++) {
            var ids=db.queryForList("SELECT id FROM "+table+" LIMIT 1",String.class);
            if(ids.isEmpty())return total;
            if(!ids.get(0).matches("[a-f0-9]{8}-0000-0000-0000-[0-9]{12}"))throw new IllegalStateException("Unowned benchmark row; will not delete");
            total+=deleteBatch(db,table,ids.get(0).substring(0,8));
        }
        throw new IllegalStateException("Too many unfinished benchmark batches");
    }
    static void seed(JdbcTemplate source,JdbcTemplate[] targets,int size,String batch,int[] counts) {
        for(int start=1;start<=size;start+=5000) {
            var all=new ArrayList<Object[]>();var groups=new ArrayList<List<Object[]>>();for(int i=0;i<32;i++)groups.add(new ArrayList<>());
            for(int i=start;i<Math.min(size+1,start+5000);i++) {
                long user=user(i);counts[(int)user]++;
                String id=HistoryVolumeTest.id(batch,i);var at=Timestamp.from(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(Math.floorMod((long)i*37,31536000)));
                var row=new Object[]{id,user,id,(long)(i%5000+1),990+(i%10)*100,state(i),at,Timestamp.from(at.toInstant().plusSeconds(300)),1L,1L,"synthetic campus","synthetic ticket","/synthetic.png"};
                all.add(row);groups.get((int)Math.floorMod(user,32)).add(row);
            }
            source.batchUpdate("INSERT INTO ux_order_single"+INSERT,all);
            for(int shard=0;shard<32;shard++)if(!groups.get(shard).isEmpty()) {
                String table=String.format(Locale.ROOT,"ux_order_read_%02d",shard);
                source.batchUpdate("INSERT INTO "+table+INSERT,groups.get(shard));
                targets[shard/16].batchUpdate("INSERT INTO "+table+INSERT,groups.get(shard));
            }
            if((start+4999)%100000==0)System.out.println("SEEDED "+(start+4999));
        }
    }
    static void validate(View view,long account,int[] counts,String batch) {
        var rows=view.list(account);assertEquals(counts[(int)account],view.count(account));assertEquals(Math.min(20,counts[(int)account]),rows.size());
        Instant previous=Instant.MAX;String previousId="~";
        for(var row:rows) {
            assertTrue(row.id().startsWith(batch+"-"));long index=Long.parseLong(row.id().substring(24));
            assertEquals(account,user(index));assertEquals(state(index),row.state());assertEquals(990+(int)(index%10)*100,row.priceCents());
            assertTrue(!row.createdAt().isAfter(previous));
            if(row.createdAt().equals(previous))assertTrue(row.id().compareTo(previousId)<=0);
            previous=row.createdAt();previousId=row.id();
        }
    }
    record Samples(List<Long> nanos,int failures) {}
    static Map<String,Object> measure(View view,int[] normal,int[] counts,String batch,String workload) throws Exception {
        var executor=Executors.newFixedThreadPool(CONCURRENCY);var start=new CountDownLatch(1);var tasks=new ArrayList<Future<Samples>>();
        long[] deadline=new long[1];
        try {
            for(int worker=0;worker<CONCURRENCY;worker++) {
                final int number=worker;
                tasks.add(executor.submit(()->{
                    var samples=new ArrayList<Long>();int failures=0;long sequence=number*1237L;start.await();
                    while(System.nanoTime()<deadline[0]) {
                        long account=workload.startsWith("mixed") && sequence%10==0?7:normal[(int)Math.floorMod(sequence,normal.length)];sequence++;
                        long began=System.nanoTime();
                        try {validate(view,account,counts,batch);samples.add(System.nanoTime()-began);}
                        catch(Throwable error){failures++;if(failures==1)System.out.println("BENCH_FAILURE "+error.getClass().getSimpleName());}
                    }
                    return new Samples(samples,failures);
                }));
            }
            long began=System.nanoTime();deadline[0]=began+SECONDS*1_000_000_000L;start.countDown();
            var nanos=new ArrayList<Long>();int failures=0;for(var task:tasks){var sample=task.get(SECONDS+20,TimeUnit.SECONDS);nanos.addAll(sample.nanos());failures+=sample.failures();}
            double elapsed=(System.nanoTime()-began)/1e9;Collections.sort(nanos);assertFalse(nanos.isEmpty());
            var result=new LinkedHashMap<String,Object>();result.put("workload",workload);result.put("completed",nanos.size());result.put("failures",failures);
            result.put("elapsedSeconds",elapsed);result.put("operationsPerSecond",nanos.size()/elapsed);
            result.put("p95Ms",nanos.get((int)Math.ceil(nanos.size()*.95)-1)/1e6);
            result.put("meanMs",nanos.stream().mapToLong(Long::longValue).average().orElseThrow()/1e6);
            result.put("_samples",nanos);
            return result;
        } finally {start.countDown();executor.shutdownNow();}
    }
}
