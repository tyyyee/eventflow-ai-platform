package com.hmdp.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LocalCoupons {
    final LocalData d;private final LocalLifeConfiguration config;private final LocalAccounts accounts;private final LocalShops shops;
    public LocalCoupons(JdbcTemplate db,Transactions tx,ObjectMapper json,LocalLifeConfiguration config,LocalAccounts accounts,LocalShops shops){d=new LocalData(db,tx,json);this.config=config;this.accounts=accounts;this.shops=shops;}
    private Instant instant(Object value){try{return Instant.parse(value.toString());}catch(Exception e){throw new Problem(400,"LOCAL_INVALID_INPUT");}}
    private String effective(Map<String,Object> row,Instant now){return "AVAILABLE".equals(row.get("state"))&&!now.isBefore((Instant)row.get("validUntil"))?"EXPIRED":row.get("state").toString();}
    private Map<String,Object> view(Map<String,Object> row,boolean includeCode){row.put("state",effective(row,d.now()));row.put("excludedDates",d.strings(row.remove("excludedDatesJson")));if(!includeCode)row.remove("redeemCode");row.put("checkedAt",d.now());return row;}
    public Object templates(long shop,Long user){config.require();shops.published(shop);var now=d.now();var result=d.rows("SELECT t.*,s.name shop_name FROM ux_coupon_template t JOIN ux_shop s ON s.id=t.shop_id WHERE t.shop_id=? AND t.state<>'DRAFT' ORDER BY t.created_at DESC LIMIT 50",shop);
        for(var row:result){row.put("claimable","PUBLISHED".equals(row.get("state"))&&((Number)row.get("remaining")).intValue()>0&&!now.isBefore((Instant)row.get("claimFrom"))&&now.isBefore((Instant)row.get("claimUntil")));
            row.put("claimed",user!=null&&d.db.queryForObject("SELECT COUNT(*) FROM ux_user_coupon WHERE user_id=? AND template_id=?",Integer.class,user,row.get("id"))>0);row.put("excludedDates",d.strings(row.remove("excludedDatesJson")));}
        return Map.of("items",result,"serverTime",now);
    }
    public Object wallet(long user){config.require();var rows=d.rows("SELECT c.*,s.name shop_name FROM ux_user_coupon c JOIN ux_shop s ON s.id=c.shop_id WHERE c.user_id=? ORDER BY c.created_at DESC,c.id LIMIT 200",user);rows.forEach(r->view(r,false));return Map.of("items",rows,"serverTime",d.now());}
    public Map<String,Object> detail(long user,String id,boolean includeCode){config.require();var row=d.one("SELECT c.*,s.name shop_name FROM ux_user_coupon c JOIN ux_shop s ON s.id=c.shop_id WHERE c.id=? AND c.user_id=?",id,user);row.put("redemptions",d.rows("SELECT id,amount_cents,discount_cents,paid_cents,created_at FROM ux_coupon_redemption WHERE coupon_id=?",id));return view(row,includeCode);}
    public Object claim(long user,String template){config.require();return d.tx.run(()->{d.lockUser(user);
        var existing=d.db.queryForList("SELECT id FROM ux_user_coupon WHERE user_id=? AND template_id=?",String.class,user,template);if(!existing.isEmpty())return detail(user,existing.get(0),false);
        var t=d.one("SELECT * FROM ux_coupon_template WHERE id=? FOR UPDATE",template);shops.published(LocalData.number(t.get("shopId")));Instant now=d.now();
        if(!"PUBLISHED".equals(t.get("state"))||now.isBefore((Instant)t.get("claimFrom"))||!now.isBefore((Instant)t.get("claimUntil")))throw new Problem(409,"COUPON_NOT_CLAIMABLE");
        int days=((Number)t.get("validDays")).intValue();Instant from=((Instant)t.get("validFrom")).isAfter(now)?(Instant)t.get("validFrom"):now;
        Instant until=(Instant)t.get("validUntil");if(days>0&&now.plusSeconds(days*86400L).isBefore(until))until=now.plusSeconds(days*86400L);
        if(!from.isBefore(until))throw new Problem(409,"COUPON_NOT_CLAIMABLE");
        if(d.db.update("UPDATE ux_coupon_template SET remaining=remaining-1 WHERE id=? AND remaining>0",template)!=1)throw new Problem(409,"COUPON_SOLD_OUT");
        String id=LocalData.id();d.db.update("INSERT INTO ux_user_coupon(id,user_id,template_id,shop_id,title,minimum_cents,discount_cents,valid_from,valid_until,terms,excluded_dates_json,redeem_code,state,updated_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'AVAILABLE',?,?)",id,user,template,t.get("shopId"),t.get("title"),t.get("minimumCents"),t.get("discountCents"),LocalData.at(from),LocalData.at(until),t.get("terms"),t.get("excludedDatesJson"),Accounts.random(),LocalData.at(now),LocalData.at(now));return detail(user,id,false);
    });}
    private Object operation(long user,String type,String key,Map<String,Object> body,Supplier<Object> action){
        accounts.requireOperator(user);accounts.limit("coupon-ops:"+user,60);if(key==null||!key.matches("[A-Za-z0-9-]{8,64}"))throw new Problem(400,"LOCAL_INVALID_INPUT");
        String hash=Accounts.digest(d.encode(new TreeMap<>(body)));
        return d.tx.run(()->{d.lockUser(user);var previous=d.rows("SELECT * FROM ux_local_operation WHERE operator_id=? AND operation_type=? AND request_key=?",user,type,key);
            if(!previous.isEmpty()){if(!hash.equals(previous.get(0).get("payloadHash")))throw new Problem(409,"LOCAL_KEY_CONFLICT");try{return d.json.readTree(previous.get(0).get("resultJson").toString());}catch(Exception e){throw new Problem(500,"LOCAL_DATA_INVALID");}}
            Object result=action.get();d.db.update("INSERT INTO ux_local_operation VALUES(?,?,?,?,?,?)",user,type,key,hash,d.encode(result),LocalData.at(d.now()));return result;});
    }
    public Object preview(long user,String code,long shop){accounts.requireOperator(user);accounts.limit("coupon-preview:"+user,30);if(code==null||!code.matches("[a-f0-9]{64}"))throw new Problem(404,"LOCAL_NOT_FOUND");
        var row=d.one("SELECT id,title,shop_id,minimum_cents,discount_cents,valid_from,valid_until,state,excluded_dates_json FROM ux_user_coupon WHERE redeem_code=? AND shop_id=?",code,shop);return view(row,false);}
    public Object redeem(long user,Map<String,Object> body){String code=LocalData.text(body.get("code"),64,64),key=LocalData.text(body.get("requestKey"),8,64);long shop=LocalData.number(body.get("shopId"));int amount=LocalData.integer(body.get("amountCents"),0,10000000);
        return operation(user,"REDEEM",key,body,()->{var c=d.one("SELECT * FROM ux_user_coupon WHERE redeem_code=? AND shop_id=? FOR UPDATE",code,shop);Instant now=d.now();
            if(!"AVAILABLE".equals(effective(c,now))||now.isBefore((Instant)c.get("validFrom"))||d.strings(c.get("excludedDatesJson")).contains(now.atZone(LocalData.ZONE).toLocalDate().toString()))throw new Problem(409,"COUPON_NOT_USABLE");
            if(amount<((Number)c.get("minimumCents")).intValue())throw new Problem(409,"COUPON_MINIMUM_NOT_MET");
            String id=LocalData.id();int discount=((Number)c.get("discountCents")).intValue();
            if(d.db.update("UPDATE ux_user_coupon SET state='USED',updated_at=? WHERE id=? AND state='AVAILABLE'",LocalData.at(now),c.get("id"))!=1)throw new Problem(409,"COUPON_NOT_USABLE");
            d.db.update("INSERT INTO ux_coupon_redemption VALUES(?,?,?,?,?,?,?)",id,c.get("id"),user,amount,discount,amount-discount,LocalData.at(now));
            d.audit(user,"COUPON_REDEEM",c.get("id").toString(),"模拟核销，无真实资金");
            return Map.of("id",id,"couponId",c.get("id"),"state","USED","amountCents",amount,"discountCents",discount,"paidCents",amount-discount,"createdAt",now,"sandbox",true);});
    }
    public Object voidCoupon(long user,String coupon,Map<String,Object> body){String reason=LocalData.text(body.get("reason"),5,300),key=LocalData.text(body.get("requestKey"),8,64);Map<String,Object> payload=new TreeMap<>(body);payload.put("couponId",coupon);
        return operation(user,"VOID",key,payload,()->{var c=d.one("SELECT * FROM ux_user_coupon WHERE id=? FOR UPDATE",coupon);Instant now=d.now();if(!"AVAILABLE".equals(effective(c,now)))throw new Problem(409,"COUPON_NOT_USABLE");
            d.db.update("UPDATE ux_user_coupon SET state='VOID',void_reason=?,updated_at=? WHERE id=? AND state='AVAILABLE'",reason,LocalData.at(now),coupon);d.audit(user,"COUPON_VOID",coupon,reason);
            return Map.of("couponId",coupon,"state","VOID","reason",reason,"updatedAt",now);});}
    public Object opsList(long user){accounts.requireOperator(user);return Map.of("items",d.rows("SELECT t.*,s.name shop_name FROM ux_coupon_template t JOIN ux_shop s ON s.id=t.shop_id ORDER BY t.created_at DESC LIMIT 200"));}
    public Object saveTemplate(long user,String id,Map<String,Object> b){accounts.requireOperator(user);long shop=LocalData.number(b.get("shopId"));shops.published(shop);String title=LocalData.text(b.get("title"),2,100),terms=LocalData.text(b.get("terms"),10,1000),state=LocalData.text(b.getOrDefault("state","DRAFT"),1,16);
        if(!Set.of("DRAFT","PUBLISHED").contains(state))throw new Problem(400,"LOCAL_INVALID_INPUT");
        int minimum=LocalData.integer(b.get("minimumCents"),1,10000000),discount=LocalData.integer(b.get("discountCents"),1,minimum),capacity=LocalData.integer(b.get("capacity"),1,1000000),days=LocalData.integer(b.getOrDefault("validDays",7),0,365);
        Instant claimFrom=instant(b.get("claimFrom")),claimUntil=instant(b.get("claimUntil")),validFrom=instant(b.get("validFrom")),validUntil=instant(b.get("validUntil"));if(!claimFrom.isBefore(claimUntil)||!validFrom.isBefore(validUntil)||!claimUntil.isBefore(validUntil))throw new Problem(400,"LOCAL_INVALID_INPUT");
        var excluded=b.getOrDefault("excludedDates",List.of());if(!(excluded instanceof List<?> dates)||dates.size()>100)throw new Problem(400,"LOCAL_INVALID_INPUT");try{for(Object date:dates)LocalDate.parse(date.toString());}catch(Exception e){throw new Problem(400,"LOCAL_INVALID_INPUT");}
        String target=id==null?LocalData.id():id;
        return d.tx.run(()->{if(id!=null){var old=d.one("SELECT * FROM ux_coupon_template WHERE id=? FOR UPDATE",id);if(!old.get("state").equals("DRAFT")||((Number)old.get("version")).longValue()!=LocalData.version(b))throw new Problem(409,"COUPON_RULES_FROZEN");
                d.db.update("UPDATE ux_coupon_template SET shop_id=?,title=?,minimum_cents=?,discount_cents=?,capacity=?,remaining=?,claim_from=?,claim_until=?,valid_from=?,valid_until=?,valid_days=?,terms=?,excluded_dates_json=?,state=?,version=version+1 WHERE id=?",shop,title,minimum,discount,capacity,capacity,LocalData.at(claimFrom),LocalData.at(claimUntil),LocalData.at(validFrom),LocalData.at(validUntil),days,terms,d.encode(dates),state,id);
            }else d.db.update("INSERT INTO ux_coupon_template(id,shop_id,title,minimum_cents,discount_cents,capacity,remaining,claim_from,claim_until,valid_from,valid_until,valid_days,terms,excluded_dates_json,state,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",target,shop,title,minimum,discount,capacity,capacity,LocalData.at(claimFrom),LocalData.at(claimUntil),LocalData.at(validFrom),LocalData.at(validUntil),days,terms,d.encode(dates),state,LocalData.at(d.now()));
            d.audit(user,"COUPON_TEMPLATE_SAVE",target,state);return d.one("SELECT * FROM ux_coupon_template WHERE id=?",target);});
    }
    public Object templateState(long user,String id,Map<String,Object> b){accounts.requireOperator(user);String state=LocalData.text(b.get("state"),1,16);if(!Set.of("PUBLISHED","PAUSED","ENDED").contains(state))throw new Problem(400,"LOCAL_INVALID_INPUT");return d.tx.run(()->{
        var row=d.one("SELECT * FROM ux_coupon_template WHERE id=? FOR UPDATE",id);if("ENDED".equals(row.get("state"))||"DRAFT".equals(row.get("state"))||((Number)row.get("version")).longValue()!=LocalData.version(b))throw new Problem(409,"LOCAL_VERSION_CONFLICT");
        d.db.update("UPDATE ux_coupon_template SET state=?,version=version+1 WHERE id=?",state,id);d.audit(user,"COUPON_TEMPLATE_STATE",id,state);return d.one("SELECT * FROM ux_coupon_template WHERE id=?",id);});}
}
