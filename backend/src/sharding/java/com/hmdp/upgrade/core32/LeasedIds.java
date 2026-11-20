package com.hmdp.upgrade.core32;

import com.hmdp.upgrade.GlobalIds;
import com.hmdp.upgrade.Problem;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Persistent worker lease and reserved timestamp horizon prevent unsafe worker reuse after a crash. */
public final class LeasedIds implements GlobalIds,AutoCloseable {
    private final JdbcTemplate source;private final String owner=UUID.randomUUID().toString();
    private final int worker;private final SnowflakeIds ids;private volatile long leaseDeadline,upperTimestamp;
    private final ScheduledExecutorService renewal=Executors.newSingleThreadScheduledExecutor(task->{var thread=new Thread(task,"core32-id-lease");thread.setDaemon(true);return thread;});
    public LeasedIds(JdbcTemplate source){
        this.source=source;int selected=-1;
        for(int n=0;n<1024;n++){
            source.update("INSERT IGNORE INTO ux_core_id_worker(worker_id,owner,expires_at,reserved_until) VALUES(?,'',TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)),0)",n);
            if(source.update("UPDATE ux_core_id_worker SET owner=?,expires_at=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3)),reserved_until=UNIX_TIMESTAMP(TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3)))*1000 WHERE worker_id=? AND expires_at<=CURRENT_TIMESTAMP(3) AND reserved_until<UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000",owner,n)==1){selected=n;break;}
        }
        if(selected<0)throw new IllegalStateException("No safely reusable Snowflake worker available");
        worker=selected;ids=new SnowflakeIds(worker);refreshHorizon();
        // Renew during slow application startup as well as during normal traffic.
        // Sharing the business scheduler can expire the lease before Spring finishes starting.
        renewal.scheduleWithFixedDelay(this::renew,10,10,TimeUnit.SECONDS);
    }
    private void refreshHorizon(){upperTimestamp=source.queryForObject("SELECT reserved_until FROM ux_core_id_worker WHERE worker_id=? AND owner=?",Long.class,worker,owner);leaseDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);}
    public synchronized void renew(){
        try{if(source.update("UPDATE ux_core_id_worker SET expires_at=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3)),reserved_until=UNIX_TIMESTAMP(TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3)))*1000 WHERE worker_id=? AND owner=? AND expires_at>CURRENT_TIMESTAMP(3)",worker,owner)!=1){leaseDeadline=0;return;}refreshHorizon();}catch(RuntimeException unavailable){/* nextId fails closed at the existing local deadline */}
    }
    @Override public long nextId(){if(System.nanoTime()>=leaseDeadline||System.currentTimeMillis()>=upperTimestamp)throw new Problem(503,"ID_WORKER_LEASE_EXPIRED");try{return ids.nextId();}catch(IllegalStateException invalidClock){throw new Problem(503,"ID_CLOCK_OR_SEQUENCE_UNAVAILABLE");}}
    public int worker(){return worker;}
    @Override public void close(){renewal.shutdownNow();source.update("UPDATE ux_core_id_worker SET expires_at=CURRENT_TIMESTAMP(3) WHERE worker_id=? AND owner=?",worker,owner);}
}
