package com.hmdp.upgrade.core32;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic PRIMARY-order SQL tests. Never connects to catalog/demo schemas. */
@EnabledIfEnvironmentVariable(named="CORE32_CAPACITY",matches="true")
class CoreCapacityBenchmarkTest {
    static final String SCHEMA="qupiao_core_capacity";
    static final int USERS=4096,SECONDS=4,ROUNDS=3;
    final Path evidence=Path.of("../docs/verification/2026-10-27-core32-capacity");
    final Path raw=Path.of("../.local/core32-capacity");
    final ObjectMapper json=new ObjectMapper();
    final Map<String,Object> report=new LinkedHashMap<>();
    final List<Map<String,Object>> rounds=new ArrayList<>(),volumes=new ArrayList<>();
    final AtomicLong serial=new AtomicLong();
    String run=Long.toString(System.currentTimeMillis(),36);
    static long user(long index){return 10000+(index-1)%USERS;}
    static String seedId(long index){return "s"+String.format(Locale.ROOT,"%012d",index);}
    static HikariDataSource pool(int port,int maximum){var c=new HikariConfig();c.setJdbcUrl(url(port));c.setUsername("qupiao");c.setPassword(System.getenv("CORE32_PASSWORD"));c.setMaximumPoolSize(maximum);c.setMinimumIdle(0);c.setConnectionTimeout(2000);c.setPoolName("capacity-single");return new HikariDataSource(c);}
    static String url(int port){return "jdbc:mysql://127.0.0.1:"+port+"/"+SCHEMA+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&rewriteBatchedStatements=true&socketTimeout=10000";}
    void save()throws Exception{Files.createDirectories(evidence);json.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("benchmark.json").toFile(),report);}
    static String resource()throws Exception{try(var in=CoreStore.class.getResourceAsStream("/db/core32/tables.sql")){return new String(Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}}
    interface View {
        String name();JdbcTemplate db(long user);String table(String logical,long user);<T>T tx(long user,Supplier<T> action);List<HikariDataSource> pools();
    }
    static View single(HikariDataSource pool){var db=new JdbcTemplate(pool);db.setQueryTimeout(3);var tx=new TransactionTemplate(new DataSourceTransactionManager(pool));tx.setIsolationLevel(2);tx.setTimeout(8);
        return new View(){public String name(){return "single";}public JdbcTemplate db(long u){return db;}public String table(String t,long u){return t;}public<T>T tx(long u,Supplier<T>a){return tx.execute(s->a.get());}public List<HikariDataSource>pools(){return List.of(pool);}};}
    static View sharded(CoreStore store,boolean logical){var pools=new ArrayList<HikariDataSource>();var transactions=new ArrayList<TransactionTemplate>();for(int i=0;i<4;i++){var pool=(HikariDataSource)store.physical(i*8).getDataSource();pools.add(pool);var tx=new TransactionTemplate(new DataSourceTransactionManager(pool));tx.setIsolationLevel(2);tx.setTimeout(8);transactions.add(tx);}
        return new View(){public String name(){return logical?"four-core32":"four-direct";}public JdbcTemplate db(long u){return logical?store.db():store.physical(CoreRouteAlgorithm.shard(u));}public String table(String t,long u){return logical?t:t+"_"+CoreRouteAlgorithm.shard(u)%8;}public<T>T tx(long u,Supplier<T>a){return logical?store.tx(u,a):transactions.get(CoreRouteAlgorithm.shard(u)/8).execute(s->a.get());}public List<HikariDataSource>pools(){return pools;}};}
    static final String ORDER_COLUMNS="(id,user_id,request_id,activity_id,price_cents,state,confirm_until,created_at) VALUES(?,?,?,?,?,?,?,?)";
    void seed(JdbcTemplate single,CoreStore store,int from,int until)throws Exception{
        long began=System.nanoTime();
        for(int start=from;start<=until;start+=5000){var all=new ArrayList<Object[]>();var groups=new ArrayList<List<Object[]>>();for(int n=0;n<32;n++)groups.add(new ArrayList<>());
            for(int i=start;i<=Math.min(until,start+4999);i++){long u=user(i);var at=Timestamp.from(Instant.parse("2026-01-01T00:00:00Z").plusMillis(i));var row=new Object[]{seedId(i),u,seedId(i),(long)i,990,"CONFIRMED",Timestamp.from(at.toInstant().plusSeconds(300)),at};all.add(row);groups.get(CoreRouteAlgorithm.shard(u)).add(row);}
            single.batchUpdate("INSERT IGNORE INTO cx_order"+ORDER_COLUMNS,all);
            for(int n=0;n<32;n++)if(!groups.get(n).isEmpty())store.physical(n).batchUpdate("INSERT IGNORE INTO cx_order_"+n%8+ORDER_COLUMNS,groups.get(n));
            if(Math.min(until,start+4999)%100000==0)System.out.println("CAPACITY SEEDED "+Math.min(until,start+4999));
        }report.put("lastSeedSeconds",(System.nanoTime()-began)/1e9);save();
    }
    void validateRead(View view,long u,int rows){var db=view.db(u);var data=db.queryForList("SELECT id,user_id,activity_id,price_cents,state,created_at FROM "+view.table("cx_order",u)+" WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 20",u);
        int expected=rows/USERS+((u-10000)<rows%USERS?1:0);assertEquals(expected,db.queryForObject("SELECT COUNT(*) FROM "+view.table("cx_order",u)+" WHERE user_id=?",Integer.class,u));assertEquals(Math.min(20,expected),data.size());
        for(var r:data){assertEquals(u,((Number)r.get("user_id")).longValue());long index=Long.parseLong(r.get("id").toString().substring(1));assertEquals(u,user(index));assertEquals(index,((Number)r.get("activity_id")).longValue());assertEquals("CONFIRMED",r.get("state"));}
    }
    void write(View view,long u){long sequence=serial.incrementAndGet();String id="w"+run+"_"+sequence,order="o"+id;long activity=10000000+sequence;var db=view.db(u);var at=Timestamp.from(Instant.now());var due=Timestamp.from(at.toInstant().plusSeconds(300));
        view.tx(u,()->{db.queryForObject("SELECT user_id FROM "+view.table("cx_user",u)+" WHERE user_id=? FOR UPDATE",Long.class,u);
            db.update("INSERT INTO "+view.table("cx_request",u)+"(id,user_id,request_key,activity_id,order_id,state,created_at,deadline) VALUES(?,?,?,?,?,'ACCEPTED',?,?)",id,u,id,activity,order,at,due);
            db.update("INSERT INTO "+view.table("cx_outbox",u)+"(id,user_id,request_id,activity_id,created_at,next_at) VALUES(?,?,?,?,?,?)","b"+id,u,id,activity,at,at);return null;});
        view.tx(u,()->{db.queryForObject("SELECT id FROM "+view.table("cx_request",u)+" WHERE user_id=? AND id=? FOR UPDATE",String.class,u,id);
            db.update("INSERT INTO "+view.table("cx_order",u)+ORDER_COLUMNS,order,u,id,activity,990,"PENDING_CONFIRM",due,at);
            assertEquals(1,db.update("UPDATE "+view.table("cx_request",u)+" SET state='SUCCEEDED',completed_at=? WHERE user_id=? AND id=? AND state='ACCEPTED'",at,u,id));
            db.update("INSERT INTO "+view.table("cx_close",u)+"(request_id,user_id,due_at) VALUES(?,?,?)",id,u,due);return null;});
    }
    static final class PoolProbe implements AutoCloseable {
        final List<HikariDataSource> pools;final int[] active,pending;final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
        PoolProbe(List<HikariDataSource> pools){this.pools=pools;active=new int[pools.size()];pending=new int[pools.size()];timer.scheduleAtFixedRate(this::sample,0,20,TimeUnit.MILLISECONDS);}
        synchronized void sample(){for(int i=0;i<pools.size();i++){var mx=pools.get(i).getHikariPoolMXBean();if(mx!=null){active[i]=Math.max(active[i],mx.getActiveConnections());pending[i]=Math.max(pending[i],mx.getThreadsAwaitingConnection());}}}
        synchronized List<Map<String,Object>> result(){var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<pools.size();i++)rows.add(Map.of("pool",pools.get(i).getPoolName(),"maximum",pools.get(i).getMaximumPoolSize(),"peakActive",active[i],"peakWaiting",pending[i]));return rows;}
        public void close(){timer.shutdownNow();sample();}
    }
    record Sample(long ns,boolean success,String error){}
    void measure(View view,String workload,int workers,int rows,int round)throws Exception{
        var executor=Executors.newFixedThreadPool(workers);var start=new CountDownLatch(1);var deadline=new long[1];var futures=new ArrayList<Future<List<Sample>>>();
        try(var probe=new PoolProbe(view.pools())){
            for(int w=0;w<workers;w++){final int worker=w;futures.add(executor.submit(()->{var samples=new ArrayList<Sample>();long sequence=worker*1237L;start.await();while(System.nanoTime()<deadline[0]){long u=workload.equals("write-hot-user")?10000:10000+Math.floorMod(sequence++*31,USERS);long began=System.nanoTime();try{if(workload.equals("read"))validateRead(view,u,rows);else write(view,u);samples.add(new Sample(System.nanoTime()-began,true,""));}catch(Throwable e){samples.add(new Sample(System.nanoTime()-began,false,e.getClass().getSimpleName()));}}return samples;}));}
            long began=System.nanoTime();deadline[0]=began+SECONDS*1000000000L;start.countDown();var samples=new ArrayList<Sample>();for(var f:futures)samples.addAll(f.get(SECONDS+30,TimeUnit.SECONDS));double elapsed=(System.nanoTime()-began)/1e9;probe.close();
            var times=samples.stream().map(Sample::ns).sorted().toList();long completed=samples.stream().filter(Sample::success).count();var errors=new TreeMap<String,Long>();samples.stream().filter(x->!x.success()).forEach(x->errors.merge(x.error(),1L,Long::sum));
            String name=rows+"-"+workload+"-"+workers+"-"+view.name()+"-"+round+".csv";Files.createDirectories(raw);try(var writer=Files.newBufferedWriter(raw.resolve(name))){writer.write("latency_ns,success,error\n");for(var s:samples)writer.write(s.ns()+","+s.success()+","+s.error()+"\n");}
            var item=new LinkedHashMap<String,Object>();item.put("mode",view.name());item.put("workload",workload);item.put("seedOrders",rows);item.put("concurrency",workers);item.put("round",round);item.put("attempts",samples.size());item.put("completed",completed);item.put("errors",errors);item.put("elapsedSeconds",elapsed);item.put("operationsPerSecond",completed/elapsed);item.put("p95Ms",times.get((int)Math.ceil(times.size()*.95)-1)/1e6);item.put("pools",probe.result());item.put("raw",name);rounds.add(item);save();
            System.out.println("CAPACITY "+view.name()+" "+workload+" n="+rows+" c="+workers+" round="+round+" qps="+Math.round(completed/elapsed)+" p95="+item.get("p95Ms")+" errors="+errors);
        }finally{start.countDown();executor.shutdownNow();}
    }
    @Test void primaryOrderVolumeReadWriteConnectionsAndHotUser()throws Exception{
        report.put("startedAt",Instant.now().toString());report.put("scope","Synthetic primary-order SQL/JDBC benchmark. Persistence stages only; excludes source inventory, Redis, Kafka and HTTP/auth. Four-direct is diagnostic, not application performance.");report.put("rounds",rounds);report.put("volumes",volumes);report.put("conditions",Map.of("users",USERS,"secondsPerRound",SECONDS,"rounds",ROUNDS,"totalConnectionsPerMode",24,"totalBufferPoolBytesPerMode",268435456,"host","all MySQL processes and client share one Windows host/disk","seed","deterministic order rows only; request/outbox/close families populated by write samples"));save();
        try(var source=pool(33307,24)){
            var db=new JdbcTemplate(source);assertEquals(SCHEMA,db.queryForObject("SELECT DATABASE()",String.class));new ResourceDatabasePopulator(new ByteArrayResource(resource().replace("__N__","").getBytes(java.nio.charset.StandardCharsets.UTF_8))).execute(source);
            try(var store=new CoreStore(new String[]{url(33400),url(33401),url(33402),url(33403)},"qupiao",System.getenv("CORE32_PASSWORD"),true)){
                assertEquals(4,store.topology().get("independentInstances"));
                var views=List.of(single(source),sharded(store,true),sharded(store,false));
                for(int i=0;i<USERS;i++){long u=10000+i;var at=Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));db.update("INSERT IGNORE INTO cx_user(user_id,username,display_name,created_at) VALUES(?,?,?,?)",u,"mock_"+u,"mock",at);store.db().update("INSERT IGNORE INTO cx_user(user_id,username,display_name,created_at) VALUES(?,?,?,?)",u,"mock_"+u,"mock",at);}
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM cx_order WHERE id NOT LIKE 's%'",Integer.class),"Prior write run found: use a fresh capacity schema or export its results first.");
                for(int size:new int[]{100000,1000000}){
                    int previous=db.queryForObject("SELECT COUNT(*) FROM cx_order",Integer.class);if(previous>size)throw new IllegalStateException("Use a fresh capacity schema for increasing-size comparison");seed(db,store,previous+1,size);
                    db.execute("ANALYZE TABLE cx_order");var distribution=new ArrayList<Map<String,Object>>();long total=0;for(int s=0;s<32;s++){var physical=store.physical(s);String table="cx_order_"+s%8;physical.execute("ANALYZE TABLE "+table);long count=physical.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);total+=count;assertEquals(0,physical.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE MOD(user_id,32)<>?",Integer.class,s));var stats=physical.queryForMap("SELECT data_length,index_length FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",table);distribution.add(Map.of("shard",s,"rows",count,"storage",stats));}assertEquals(size,total);
                    var volume=new LinkedHashMap<String,Object>();volume.put("seedOrders",size);volume.put("distribution",distribution);volume.put("singleStorage",db.queryForMap("SELECT data_length,index_length FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='cx_order'"));volume.put("singleExplain",db.queryForList("EXPLAIN SELECT * FROM cx_order WHERE user_id=10086 ORDER BY created_at DESC,id DESC LIMIT 20"));volume.put("shardExplain",store.physical(CoreRouteAlgorithm.shard(10086)).queryForList("EXPLAIN SELECT * FROM cx_order_6 WHERE user_id=10086 ORDER BY created_at DESC,id DESC LIMIT 20"));volumes.add(volume);save();
                    for(var view:views)for(int i=0;i<150;i++)validateRead(view,10000+i,size);
                    for(int concurrency:new int[]{16,64})for(int round=1;round<=ROUNDS;round++){var order=new ArrayList<>(views);Collections.rotate(order,round-1);for(var view:order)measure(view,"read",concurrency,size,round);}
                }
                for(String workload:List.of("write-distributed","write-hot-user"))for(int round=1;round<=ROUNDS;round++){var order=new ArrayList<>(views);Collections.rotate(order,round-1);for(var view:order)measure(view,workload,32,1000000,round);}
                report.put("topology",store.topology());report.put("singleFinalOrders",db.queryForObject("SELECT COUNT(*) FROM cx_order",Long.class));var validations=new ArrayList<Map<String,Object>>();
                for(var view:List.of(views.get(0),views.get(2))){long requests=0,orders=0,outbox=0,violations=0;for(int s=0;s<(view.name().equals("single")?1:32);s++){var target=view.name().equals("single")?db:store.physical(s);String suffix=view.name().equals("single")?"":"_"+s%8;requests+=target.queryForObject("SELECT COUNT(*) FROM cx_request"+suffix,Long.class);orders+=target.queryForObject("SELECT COUNT(*) FROM cx_order"+suffix+" WHERE id LIKE 'ow%'",Long.class);outbox+=target.queryForObject("SELECT COUNT(*) FROM cx_outbox"+suffix,Long.class);violations+=target.queryForObject("SELECT COUNT(*) FROM cx_request"+suffix+" r WHERE r.state='SUCCEEDED' AND NOT EXISTS(SELECT 1 FROM cx_order"+suffix+" o WHERE o.request_id=r.id AND o.user_id=r.user_id)",Long.class);}assertEquals(0,violations);assertEquals(requests,outbox);validations.add(Map.of("mode",view.name(),"requests",requests,"orders",orders,"outbox",outbox,"stateViolations",violations));}report.put("writeValidation",validations);
                report.put("completedAt",Instant.now().toString());save();
            }
        }
    }
}
