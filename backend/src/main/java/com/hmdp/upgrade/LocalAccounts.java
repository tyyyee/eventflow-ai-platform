package com.hmdp.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class LocalAccounts {
    final LocalData data;private final LocalLifeConfiguration config;
    private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(12);
    private record Window(long minute,int count){}
    private final ConcurrentHashMap<String,Window> limits=new ConcurrentHashMap<>();
    public LocalAccounts(JdbcTemplate db,Transactions tx,ObjectMapper json,LocalLifeConfiguration config){data=new LocalData(db,tx,json);this.config=config;}
    void limit(String key,int max){long minute=System.currentTimeMillis()/60000;
        if(limits.size()>=5000)limits.entrySet().removeIf(e->e.getValue().minute()!=minute);
        if(limits.size()>=5000&&!limits.containsKey(key))throw new Problem(503,"AUTH_BUSY");
        var w=limits.compute(key,(k,v)->new Window(minute,v==null||v.minute()!=minute?1:v.count()+1));
        if(w.count()>max)throw new Problem(429,"AUTH_RATE_LIMITED");
    }
    public boolean operator(long user){return config.enabled&&data.db.queryForObject("SELECT COUNT(*) FROM ux_local_profile WHERE user_id=? AND role='OPERATOR'",Integer.class,user)>0;}
    public void requireOperator(long user){config.require();if(!operator(user))throw new Problem(403,"OPERATOR_REQUIRED");}
    public Map<String,Object> profile(long user){config.require();var p=data.one("SELECT a.id,a.username,a.display_name,COALESCE(p.bio,'') bio,p.avatar_id,COALESCE(p.role,'CUSTOMER') role FROM ux_account a LEFT JOIN ux_local_profile p ON p.user_id=a.id WHERE a.id=?",user);
        p.put("hasRecoveryCode",data.db.queryForObject("SELECT COUNT(*) FROM ux_account_recovery WHERE user_id=?",Integer.class,user)>0);return p;}
    public Map<String,Object> profile(long user,Map<String,Object> body){config.require();String name=LocalData.text(body.get("displayName"),1,24),bio=LocalData.text(body.get("bio"),0,200);
        return data.tx.run(()->{data.lockUser(user);data.db.update("UPDATE ux_account SET display_name=? WHERE id=?",name,user);
            data.db.update("INSERT INTO ux_local_profile(user_id,bio,role,updated_at) VALUES(?,?,'CUSTOMER',?) ON DUPLICATE KEY UPDATE bio=VALUES(bio),updated_at=VALUES(updated_at)",user,bio,LocalData.at(data.now()));return profile(user);});}
    void verifyCurrent(long user,String password){Accounts.password(password);String hash=data.db.queryForObject("SELECT password_hash FROM ux_account WHERE id=?",String.class,user);if(!encoder.matches(password,hash))throw new Problem(401,"BAD_CREDENTIALS");}
    private String rotate(long user){String code=Accounts.random();data.db.update("INSERT INTO ux_account_recovery(user_id,code_hash,updated_at) VALUES(?,?,?) ON DUPLICATE KEY UPDATE code_hash=VALUES(code_hash),updated_at=VALUES(updated_at)",user,Accounts.digest(code),LocalData.at(data.now()));return code;}
    public Object recoveryCode(long user,String password){config.require();limit("code:"+user,5);return data.tx.run(()->{data.lockUser(user);verifyCurrent(user,password);return Map.of("recoveryCode",rotate(user),"message","请保存恢复码；生成新码后旧码立即失效。");});}
    public void changePassword(long user,String oldPassword,String next){config.require();limit("password:"+user,5);Accounts.password(next);
        String hash=encoder.encode(next);data.tx.run(()->{data.lockUser(user);verifyCurrent(user,oldPassword);data.db.update("UPDATE ux_account SET password_hash=? WHERE id=?",hash,user);data.db.update("DELETE FROM ux_session WHERE user_id=?",user);return null;});}
    public Object recover(String username,String code,String next,String remote){config.require();limit("recover-ip:"+remote,10);String name=Accounts.username(username);limit("recover-name:"+name,5);Accounts.password(next);
        if(code==null||!code.matches("[a-f0-9]{64}"))throw new Problem(400,"RECOVERY_FAILED");
        String hash=encoder.encode(next);
        return data.tx.run(()->{var ids=data.db.queryForList("SELECT id FROM ux_account WHERE username=?",Long.class,name);if(ids.isEmpty())throw new Problem(400,"RECOVERY_FAILED");long user=ids.get(0);data.lockUser(user);
            var stored=data.db.queryForList("SELECT code_hash FROM ux_account_recovery WHERE user_id=?",String.class,user);
            if(stored.isEmpty()||!MessageDigest.isEqual(Accounts.digest(code).getBytes(StandardCharsets.UTF_8),stored.get(0).getBytes(StandardCharsets.UTF_8)))throw new Problem(400,"RECOVERY_FAILED");
            data.db.update("UPDATE ux_account SET password_hash=? WHERE id=?",hash,user);data.db.update("DELETE FROM ux_session WHERE user_id=?",user);
            return Map.of("recoveryCode",rotate(user),"message","密码已更新，旧会话和旧恢复码已失效，请重新登录。");});}
}
