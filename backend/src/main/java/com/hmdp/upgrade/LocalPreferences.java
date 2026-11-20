package com.hmdp.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Only the account owner can explicitly change long-term preferences. Model tools only draft changes. */
@Service
public class LocalPreferences {
    final LocalData d;final LocalLifeConfiguration config;
    public static final Set<String> FIELDS=Set.of("campus","maxPriceCents","landmark","maxDistanceMeters","preferredCategories","preferredTags","excludedCategories","avoidedTags");
    public static final List<String> TAGS=List.of("安静","适合自习","聚餐","外带","不辣选项","室内","阅读","运动","咖啡","茶饮","麻辣","甜品","手作");
    public LocalPreferences(JdbcTemplate db,Transactions tx,ObjectMapper json,LocalLifeConfiguration config){d=new LocalData(db,tx,json);this.config=config;}
    public Map<String,Object> read(long user){config.require();var rows=d.rows("SELECT * FROM ux_life_preference WHERE user_id=?",user);if(rows.isEmpty())return Map.of("enabled",true,"version",0,"values",Map.of(),"tags",TAGS,"categories",LocalShops.CATEGORIES);
        var row=rows.get(0);try{return Map.of("enabled",row.get("enabled"),"version",row.get("version"),"values",d.json.readTree(row.get("preferencesJson").toString()),"updatedAt",row.get("updatedAt"),"tags",TAGS,"categories",LocalShops.CATEGORIES);}catch(Exception e){throw new Problem(500,"LOCAL_DATA_INVALID");}}
    public Map<String,Object> validate(Map<String,Object> values){var out=new LinkedHashMap<String,Object>();for(var entry:values.entrySet()){
        String key=entry.getKey();Object value=entry.getValue();if(!FIELDS.contains(key))throw new Problem(400,"LOCAL_INVALID_INPUT");if(value==null||"".equals(value))continue;
        switch(key){
            case "campus"->{if(!Set.of("主校区","东校区","南校区").contains(value))throw new Problem(400,"LOCAL_INVALID_INPUT");out.put(key,value);}
            case "landmark"->{if(LocalShops.LANDMARKS.stream().noneMatch(x->x.get("id").equals(value)))throw new Problem(400,"LOCAL_INVALID_INPUT");out.put(key,value);}
            case "maxPriceCents"->out.put(key,LocalData.integer(value,0,1000000));
            case "maxDistanceMeters"->out.put(key,LocalData.integer(value,1,100000));
            default->{if(!(value instanceof List<?> list)||list.size()>8)throw new Problem(400,"LOCAL_INVALID_INPUT");var allowed=key.endsWith("Categories")?LocalShops.CATEGORIES:TAGS;var checked=list.stream().map(x->LocalData.text(x,1,30)).distinct().toList();if(!allowed.containsAll(checked))throw new Problem(400,"LOCAL_INVALID_INPUT");out.put(key,checked);}
        }
    }return out;}
    @SuppressWarnings("unchecked") public Object save(long user,Map<String,Object> body){config.require();if(!(body.get("values") instanceof Map<?,?> values)||!(body.get("enabled") instanceof Boolean enabled))throw new Problem(400,"LOCAL_INVALID_INPUT");var checked=validate((Map<String,Object>)values);int version=LocalData.integer(body.get("version"),0,Integer.MAX_VALUE);
        return d.tx.run(()->{d.lockUser(user);var rows=d.rows("SELECT version FROM ux_life_preference WHERE user_id=? FOR UPDATE",user);if(rows.isEmpty()){if(version!=0)throw new Problem(409,"LOCAL_VERSION_CONFLICT");d.db.update("INSERT INTO ux_life_preference VALUES(?,?,?,1,?)",user,enabled,d.encode(checked),LocalData.at(d.now()));}else{if(((Number)rows.get(0).get("version")).intValue()!=version)throw new Problem(409,"LOCAL_VERSION_CONFLICT");d.db.update("UPDATE ux_life_preference SET enabled=?,preferences_json=?,version=version+1,updated_at=? WHERE user_id=?",enabled,d.encode(checked),LocalData.at(d.now()),user);}return read(user);});}
    public Object clear(long user){config.require();return d.tx.run(()->{d.lockUser(user);d.db.update("DELETE FROM ux_life_preference WHERE user_id=?",user);return read(user);});}
    @SuppressWarnings("unchecked") public Map<String,Object> values(long user){var p=read(user);if(!Boolean.TRUE.equals(p.get("enabled")))return Map.of();return d.json.convertValue(p.get("values"),Map.class);}
    public Map<String,String> defaults(long user){var p=values(user);var result=new LinkedHashMap<String,String>();for(String k:List.of("campus","maxPriceCents","landmark","maxDistanceMeters"))if(p.containsKey(k))result.put(k,p.get(k).toString());for(String k:List.of("excludedCategories","avoidedTags"))if(p.get(k) instanceof List<?> list&&!list.isEmpty())result.put(k,String.join(",",list.stream().map(Object::toString).toList()));return result;}
    public double affinity(Map<String,Object> shop,long user){var p=values(user);return affinity(shop,p);}
    public double affinity(Map<String,Object> shop,Map<String,Object> p){double score=0;Object categories=p.get("preferredCategories");if(categories instanceof List<?> c&&c.contains(shop.get("category")))score+=8;Object tags=p.get("preferredTags");if(tags instanceof List<?> wanted&&shop.get("tags") instanceof List<?> actual)score+=actual.stream().filter(wanted::contains).count()*3;return score;}
}
