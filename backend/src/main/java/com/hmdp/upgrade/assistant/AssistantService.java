package com.hmdp.upgrade.assistant;

import com.fasterxml.jackson.databind.*;
import com.hmdp.upgrade.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.hmdp.upgrade.assistant.AssistantData.*;

@Service
public class AssistantService {
    private final JdbcTemplate db;private final Transactions tx;private final ObjectMapper json;private final AssistantRuntime runtime;
    private static final Set<String> ACTIVE=Set.of("QUEUED","RUNNING");
    static final class LostLease extends RuntimeException {}
    public AssistantService(JdbcTemplate db,Transactions tx,ObjectMapper json,AssistantRuntime runtime){this.db=db;this.tx=tx;this.json=json;this.runtime=runtime;}
    private Instant now(){return db.queryForObject("SELECT CURRENT_TIMESTAMP(3)",Timestamp.class).toInstant();}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Assistant serialization failed");}}
    private <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("Assistant state unreadable");}}
    private void enabled(){if(runtime.mode().equals("disabled"))throw new Problem(503,"ASSISTANT_DISABLED");}
    public List<SessionItem> list(long user){return db.query("SELECT id,updated_at FROM ux_assistant_session WHERE user_id=? ORDER BY updated_at DESC,id DESC LIMIT 50",(r,n)->new SessionItem(r.getString(1),r.getTimestamp(2).toInstant()),user);}
    public Session create(long user){
        enabled();String id=UUID.randomUUID().toString();
        tx.run(()->{
            if(db.queryForList("SELECT id FROM ux_account WHERE id=? FOR UPDATE",Long.class,user).isEmpty())throw new Problem(401,"CUSTOMER_SESSION_REQUIRED");
            if(db.queryForObject("SELECT COUNT(*) FROM ux_assistant_session WHERE user_id=?",Integer.class,user)>=50)throw new Problem(429,"ASSISTANT_SESSION_LIMIT");
            Instant now=now();db.update("INSERT INTO ux_assistant_session(id,user_id,state_json,created_at,updated_at) VALUES(?,?,?,?,?)",id,user,encode(new State()),Timestamp.from(now),Timestamp.from(now));return null;
        });return session(user,id);
    }
    private Map<String,Object> owned(long user,String id,boolean lock){
        var rows=db.queryForList("SELECT * FROM ux_assistant_session WHERE id=? AND user_id=?"+(lock?" FOR UPDATE":""),id,user);
        if(rows.isEmpty())throw new Problem(404,"ASSISTANT_SESSION_NOT_FOUND");return rows.get(0);
    }
    public Session session(long user,String id){
        expire();var row=owned(user,id,false);
        var turns=db.query("SELECT * FROM ux_assistant_run WHERE session_id=? AND user_id=? ORDER BY created_at,id",this::run,id,user);
        return new Session(id,runtime.mode(),decode((String)row.get("state_json"),State.class),(String)row.get("active_run"),turns);
    }
    public void delete(long user,String id){tx.run(()->{var row=owned(user,id,true);if(row.get("active_run")!=null)throw new Problem(409,"ASSISTANT_BUSY");db.update("DELETE FROM ux_assistant_session WHERE id=? AND user_id=?",id,user);return null;});}
    public Run submit(long user,String session,String key,String message){
        enabled();
        if(key==null||!key.matches("[A-Za-z0-9_-]{8,64}")||message==null||message.isBlank()||message.length()>1000||message.indexOf('\0')>=0)throw new Problem(400,"ASSISTANT_INVALID_REQUEST");
        if(message.matches("(?is).*(?:[a-f0-9]{64}|sk-[a-z0-9_-]{16,}).*")||message.matches("(?is).*(?:密码|password|token|恢复码|核销码)[：: ]+[a-z0-9_!@#$%^-]{6,}.*"))throw new Problem(400,"ASSISTANT_SENSITIVE_INPUT");
        final String input=message.trim();expire();
        String id=tx.run(()->{
            db.queryForObject("SELECT id FROM ux_assistant_quota WHERE id=1 FOR UPDATE",Integer.class);
            var row=owned(user,session,true);
            var existing=db.queryForList("SELECT id,message FROM ux_assistant_run WHERE session_id=? AND request_key=?",session,key);
            if(!existing.isEmpty()) {if(!input.equals(existing.get(0).get("message")))throw new Problem(409,"ASSISTANT_KEY_CONFLICT");return (String)existing.get(0).get("id");}
            if(row.get("active_run")!=null||db.queryForObject("SELECT COUNT(*) FROM ux_assistant_run WHERE user_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,user)>0)throw new Problem(409,"ASSISTANT_BUSY");
            if(db.queryForObject("SELECT COUNT(*) FROM ux_assistant_run WHERE session_id=?",Integer.class,session)>=40)throw new Problem(429,"ASSISTANT_TURN_LIMIT");
            if(db.queryForObject("SELECT COUNT(*) FROM ux_assistant_run WHERE status IN ('QUEUED','RUNNING')",Integer.class)>=100)throw new Problem(503,"ASSISTANT_QUEUE_FULL");
            Instant now=now();
            if(db.queryForObject("SELECT COUNT(*) FROM ux_assistant_run WHERE user_id=? AND created_at>?",Integer.class,user,Timestamp.from(now.minusSeconds(60)))>=10)throw new Problem(429,"ASSISTANT_RATE_LIMIT");
            Checkpoint cp=new Checkpoint();cp.state=decode((String)row.get("state_json"),State.class);String run=UUID.randomUUID().toString();
            db.update("INSERT INTO ux_assistant_run(id,session_id,user_id,request_key,message,status,mode,checkpoint_json,created_at,deadline) VALUES(?,?,?,?,?,'QUEUED',?,?,?,?)",
                run,session,user,key,input,runtime.mode(),encode(cp),Timestamp.from(now),Timestamp.from(now.plusSeconds(60)));
            db.update("UPDATE ux_assistant_session SET active_run=?,updated_at=? WHERE id=?",run,Timestamp.from(now),session);return run;
        });return get(user,session,id);
    }
    public Run get(long user,String session,String id){
        expire();owned(user,session,false);
        var rows=db.query("SELECT * FROM ux_assistant_run WHERE id=? AND session_id=? AND user_id=?",this::run,id,session,user);
        if(rows.isEmpty())throw new Problem(404,"ASSISTANT_RUN_NOT_FOUND");return rows.get(0);
    }
    private Run run(ResultSet r,int index)throws SQLException{
        Checkpoint cp=decode(r.getString("checkpoint_json"),Checkpoint.class);String result=r.getString("result_json");
        return new Run(r.getString("id"),r.getString("session_id"),r.getString("message"),r.getString("status"),r.getString("mode"),
            result==null?null:decode(result,Reply.class),r.getString("error_code"),cp.modelCalls,cp.toolCalls,cp.modelInFlight?null:cp.tokens,r.getTimestamp("created_at").toInstant());
    }
    public List<Step> steps(long user,String session,String run){get(user,session,run);return db.query("SELECT step_no,tool_name,outcome,elapsed_ms FROM ux_assistant_step WHERE run_id=? ORDER BY step_no",(r,n)->new Step(r.getInt(1),r.getString(2),r.getString(3),r.getLong(4)),run);}
    public void expire(){
        for(var row:db.queryForList("SELECT id,session_id FROM ux_assistant_run WHERE status IN ('QUEUED','RUNNING') AND deadline<=CURRENT_TIMESTAMP(3) ORDER BY deadline LIMIT 100")) {
            String id=(String)row.get("id");
            tx.run(()->{
                if(db.update("UPDATE ux_assistant_run SET status='TIMED_OUT',error_code='ASSISTANT_BUDGET_EXCEEDED',result_json=?,completed_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status IN ('QUEUED','RUNNING') AND deadline<=CURRENT_TIMESTAMP(3)",encode(AssistantReplies.failure("ASSISTANT_BUDGET_EXCEEDED",now())),id)==1)
                    db.update("UPDATE ux_assistant_session SET active_run=NULL WHERE id=? AND active_run=?",row.get("session_id"),id);
                return null;
            });
        }
    }
    private Claim claim(){return tx.run(()->{
        var ids=db.queryForList("SELECT id FROM ux_assistant_run WHERE deadline>CURRENT_TIMESTAMP(3) AND (status='QUEUED' OR (status='RUNNING' AND lease_until<CURRENT_TIMESTAMP(3))) ORDER BY created_at,id LIMIT 1",String.class);
        if(ids.isEmpty())return null;String id=ids.get(0),owner=UUID.randomUUID().toString();Instant now=now();
        if(db.update("UPDATE ux_assistant_run SET status='RUNNING',owner=?,lease_until=? WHERE id=? AND deadline>? AND (status='QUEUED' OR (status='RUNNING' AND lease_until<?))",owner,Timestamp.from(now.plusSeconds(30)),id,Timestamp.from(now),Timestamp.from(now))!=1)return null;
        var row=db.queryForMap("SELECT * FROM ux_assistant_run WHERE id=?",id);
        return new Claim(id,(String)row.get("session_id"),((Number)row.get("user_id")).longValue(),(String)row.get("message"),owner,(String)row.get("mode"),((Timestamp)row.get("deadline")).toInstant(),decode((String)row.get("checkpoint_json"),Checkpoint.class));
    });}
    private void save(Claim claim,Checkpoint cp){
        String value=encode(cp);if(value.length()>150000)throw new Problem(400,"ASSISTANT_CONTEXT_LIMIT");
        Instant now=now();
        if(db.update("UPDATE ux_assistant_run SET checkpoint_json=?,lease_until=? WHERE id=? AND owner=? AND status='RUNNING' AND deadline>? AND lease_until>?",
            value,Timestamp.from(now.plusSeconds(30)),claim.id(),claim.owner(),Timestamp.from(now),Timestamp.from(now))!=1)throw new LostLease();
    }
    public boolean workOne(){
        expire();Claim claim=claim();if(claim==null)return false;Checkpoint cp=claim.checkpoint();
        try {
            Reply reply=runtime.run(claim,new AssistantRuntime.Progress(){
                public void save(Checkpoint value){AssistantService.this.save(claim,value);}
                public void step(Checkpoint value,String tool,JsonNode args,JsonNode result,String outcome,long elapsed){tx.run(()->{
                    AssistantService.this.save(claim,value);
                    db.update("INSERT INTO ux_assistant_step(run_id,step_no,tool_name,arguments_json,result_json,outcome,elapsed_ms,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP(3))",
                        claim.id(),value.toolCalls,tool,encode(args),encode(result),outcome,elapsed);return null;
                });}
            });finish(claim,cp,reply,null);
        } catch(LostLease lost){/* A new worker or timeout owns the result; never overwrite it. */}
        catch(Problem failed){finish(claim,cp,AssistantReplies.failure(failed.code,now()),failed.code);}
        catch(Exception failed){finish(claim,cp,AssistantReplies.failure("ASSISTANT_EXECUTION_FAILED",now()),"ASSISTANT_EXECUTION_FAILED");}
        return true;
    }
    private void finish(Claim claim,Checkpoint cp,Reply reply,String error){
        tx.run(()->{
            Instant now=now();
            int changed=db.update("UPDATE ux_assistant_run SET status=?,checkpoint_json=?,result_json=?,error_code=?,completed_at=? WHERE id=? AND owner=? AND status='RUNNING' AND deadline>? AND lease_until>?",
                error==null?"SUCCEEDED":"FAILED",encode(cp),encode(reply),error,Timestamp.from(now),claim.id(),claim.owner(),Timestamp.from(now),Timestamp.from(now));
            if(changed==1){
                // Keep bounded context. Dynamic availability is re-read in every subsequent turn.
                cp.state.recent.add(Map.of("role","user","content",claim.message()));cp.state.recent.add(Map.of("role","assistant","content",reply.text()));
                if(cp.state.recent.size()>6)cp.state.recent=new ArrayList<>(cp.state.recent.subList(cp.state.recent.size()-6,cp.state.recent.size()));
                db.update("UPDATE ux_assistant_session SET state_json=?,active_run=NULL,updated_at=? WHERE id=? AND user_id=? AND active_run=?",encode(cp.state),Timestamp.from(now),claim.sessionId(),claim.user(),claim.id());
            }
            return null;
        });
    }
}
