package com.hmdp.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The order and close task commit together. MQ is the trigger; MySQL is the authority. */
@Component
public class DelayedClose {
    public record Event(int version,long userId,String requestId){}
    private final JdbcTemplate db;private final Trading trading;private final OrderMessages messages;
    private final ObjectMapper json=new ObjectMapper();
    @Value("${upgrade.jobs:true}") private boolean jobs;
    @Value("${upgrade.core32.enabled:false}") private boolean core32;
    @Value("${upgrade.close-topic:qupiao-close-v2}") private String topic="qupiao-close-v2";
    @Value("${upgrade.close-group:qupiao-close-v2}") private String group="qupiao-close-v2";
    @org.springframework.beans.factory.annotation.Autowired(required=false)private Faults faults;
    public final LongAdder failures=new LongAdder();
    public DelayedClose(JdbcTemplate db,Trading trading,OrderMessages messages){this.db=db;this.trading=trading;this.messages=messages;}
    @jakarta.annotation.PostConstruct void subscribe(){if(jobs&&!core32)messages.subscribe(topic,group,this::consume);}
    public void enqueue(){
        var rows=db.queryForList("SELECT request_id,user_id,due_at FROM ux_close_task WHERE done_at IS NULL AND message_sent_at IS NULL AND next_enqueue_at<=CURRENT_TIMESTAMP(3) ORDER BY next_enqueue_at LIMIT 20");
        for(var row:rows){
            String request=row.get("request_id").toString();long user=((Number)row.get("user_id")).longValue();
            try {
                messages.sendAt(topic,request,json.writeValueAsString(new Event(1,user,request)),((Timestamp)row.get("due_at")).toInstant()).get(6,TimeUnit.SECONDS);
                if(faults!=null)faults.hit("close-send-before-mark");
                db.update("UPDATE ux_close_task SET message_sent_at=CURRENT_TIMESTAMP(3) WHERE request_id=? AND done_at IS NULL AND message_sent_at IS NULL",request);
            }catch(Exception failure){
                if(failure instanceof InterruptedException)Thread.currentThread().interrupt();failures.increment();
                db.update("UPDATE ux_close_task SET next_enqueue_at=? WHERE request_id=? AND message_sent_at IS NULL AND done_at IS NULL",Timestamp.from(trading.now().plusSeconds(5)),request);break;
            }
        }
    }
    public void consume(OrderMessages.Received record)throws Exception {
        Event event;
        try {event=json.readValue(record.body(),Event.class);if(event==null||event.version()!=1||event.userId()<=0||event.requestId()==null||!event.requestId().equals(record.key()))throw new IllegalArgumentException();}
        catch(Exception invalid){MqPoison.persist(db,record,"INVALID_CLOSE_EVENT");return;}
        var tasks=db.queryForList("SELECT user_id FROM ux_close_task WHERE request_id=?",event.requestId());
        if(tasks.isEmpty())return;
        if(((Number)tasks.get(0).get("user_id")).longValue()!=event.userId()){MqPoison.persist(db,record,"CLOSE_USER_MISMATCH");return;}
        if(!close(event.requestId()))throw new IllegalStateException("Order is not due yet");
        if(faults!=null)faults.hit("close-commit-before-ack");
    }
    boolean close(String id){
        var rows=db.queryForList("SELECT user_id,done_at FROM ux_close_task WHERE request_id=?",id);
        if(rows.isEmpty()||rows.get(0).get("done_at")!=null)return true;
        var order=trading.transition(((Number)rows.get(0).get("user_id")).longValue(),id,"expire");
        if("PENDING_CONFIRM".equals(order.get("state")))return false;
        db.update("UPDATE ux_close_task SET done_at=CURRENT_TIMESTAMP(3) WHERE request_id=? AND done_at IS NULL",id);return true;
    }
    public void reconcile(){
        for(String id:db.queryForList("SELECT request_id FROM ux_close_task WHERE done_at IS NULL AND due_at<=CURRENT_TIMESTAMP(3) ORDER BY due_at LIMIT 100",String.class))close(id);
    }
    @Scheduled(fixedDelay=1000)public void tick(){if(jobs&&!core32)try{enqueue();}catch(RuntimeException failure){failures.increment();}}
    @Scheduled(fixedDelayString="${upgrade.close-fallback-ms:60000}")public void fallback(){if(jobs&&!core32)try{reconcile();}catch(RuntimeException failure){failures.increment();}}
}
