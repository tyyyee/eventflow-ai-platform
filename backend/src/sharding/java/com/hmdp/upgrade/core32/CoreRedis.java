package com.hmdp.upgrade.core32;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import com.hmdp.upgrade.Problem;
import java.util.*;

public final class CoreRedis {
    private final StringRedisTemplate redis;
    public CoreRedis(StringRedisTemplate redis){this.redis=redis;}
    private static List<String> keys(long activity){String p="cx:reserve:{"+activity+"}:";return List.of(p+"stock",p+"users",p+"requests",p+"pending",p+"epoch");}
    private static final DefaultRedisScript<String> RESERVE=new DefaultRedisScript<>("""
        local e=redis.call('GET',KEYS[5]);if not e or redis.call('EXISTS',KEYS[1])==0 then return '-1:' end
        local f=ARGV[1]..':'..ARGV[2];local s=redis.call('HGET',KEYS[3],f)
        if s then return tostring(10+tonumber(s))..':'..e end
        if redis.call('HEXISTS',KEYS[2],ARGV[1])==1 then return '2:'..e end
        if tonumber(redis.call('GET',KEYS[1]))<=0 then return '3:'..e end
        redis.call('DECR',KEYS[1]);redis.call('HSET',KEYS[2],ARGV[1],ARGV[2]);redis.call('HSET',KEYS[3],f,1);redis.call('ZADD',KEYS[4],ARGV[3],f)
        return '1:'..e
        """,String.class);
    private static final DefaultRedisScript<Long> APPLY=new DefaultRedisScript<>("""
        local epoch=redis.call('GET',KEYS[5]);if not epoch or redis.call('EXISTS',KEYS[1])==0 then return -1 end
        if epoch~=ARGV[4] then return 0 end
        local f=ARGV[1]..':'..ARGV[2];local s=tonumber(redis.call('HGET',KEYS[3],f) or '0')
        if ARGV[3]=='CONFIRM' then
          if s==1 then redis.call('HSET',KEYS[3],f,2);redis.call('ZREM',KEYS[4],f) end;return 1
        end
        if s==1 or (ARGV[3]=='RESTORE' and s==2) then
          redis.call('INCR',KEYS[1]);redis.call('HSET',KEYS[3],f,ARGV[3]=='RESTORE' and 4 or 3);redis.call('ZREM',KEYS[4],f)
          if ARGV[3]=='RELEASE' and redis.call('HGET',KEYS[2],ARGV[1])==ARGV[2] then redis.call('HDEL',KEYS[2],ARGV[1]) end
        end
        return 1
        """,Long.class);
    private static final DefaultRedisScript<Long> INITIALIZE=new DefaultRedisScript<>("""
        if redis.call('EXISTS',KEYS[1])==1 then return 0 end
        redis.call('SET',KEYS[1],ARGV[1]);redis.call('SET',KEYS[5],ARGV[2]);return 1
        """,Long.class);
    public void prepare(long activity,int available,String epoch){redis.execute(INITIALIZE,keys(activity),Integer.toString(available),epoch);}
    private static final DefaultRedisScript<Long> REBUILD=new DefaultRedisScript<>("""
        redis.call('DEL',KEYS[1],KEYS[2],KEYS[3],KEYS[4],KEYS[5]);redis.call('SET',KEYS[1],ARGV[1]);redis.call('SET',KEYS[5],ARGV[2])
        local n=tonumber(ARGV[3]);local i=4
        for j=1,n do
          redis.call('HSET',KEYS[3],ARGV[i],ARGV[i+1]);if ARGV[i+1]=='1' then redis.call('ZADD',KEYS[4],ARGV[i+2],ARGV[i]) end;i=i+3
        end
        while i<=#ARGV do redis.call('HSET',KEYS[2],ARGV[i],ARGV[i+1]);i=i+2 end
        return 1
        """,Long.class);
    public void rebuild(long activity,int available,String epoch,List<Map<String,Object>> requests){
        var arguments=new ArrayList<String>();arguments.add(Integer.toString(available));arguments.add(epoch);arguments.add(Integer.toString(requests.size()));var users=new LinkedHashMap<String,String>();
        for(var row:requests){String state=row.get("state").toString(),order=(String)row.get("order_state");String code=state.equals("ACCEPTED")?"1":order==null?"3":List.of("CANCELLED","EXPIRED").contains(order)?"4":"2";String user=row.get("user_id").toString(),key=row.get("request_key").toString();arguments.add(user+":"+key);arguments.add(code);arguments.add(Long.toString(CoreEngine.instant(row,"deadline").toEpochMilli()));if(state.equals("ACCEPTED")||order!=null)users.put(user,key);}
        users.forEach((user,key)->{arguments.add(user);arguments.add(key);});redis.execute(REBUILD,keys(activity),arguments.toArray());
    }
    public record Reservation(int code,String epoch){}
    public Reservation reserve(long activity,long user,String key){String raw=redis.execute(RESERVE,keys(activity),Long.toString(user),key,Long.toString(System.currentTimeMillis()+5000));if(raw==null)throw new Problem(503,"RESERVATION_UNAVAILABLE");String[] parts=raw.split(":",2);int code=Integer.parseInt(parts[0]);if(code<0)throw new Problem(503,"RESERVATION_RECOVERY_REQUIRED");return new Reservation(code,parts[1]);}
    public void apply(long activity,long user,String key,String kind,String epoch){Long result=redis.execute(APPLY,keys(activity),Long.toString(user),key,kind,epoch==null?"":epoch);if(result==null||result<0)throw new Problem(503,"RESERVATION_UNAVAILABLE");}
    public Set<String> expired(long activity,int limit){return redis.opsForZSet().rangeByScore(keys(activity).get(3),0,System.currentTimeMillis(),0,limit);}
    public void postpone(long activity,String field,long deadline){redis.opsForZSet().add(keys(activity).get(3),field,deadline);}
    public String epoch(long activity){return redis.opsForValue().get(keys(activity).get(4));}
    public int remaining(long activity){String value=redis.opsForValue().get(keys(activity).get(0));return value==null?-1:Integer.parseInt(value);}
}
