package com.hmdp.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LocalCommunity {
    final LocalData d;private final LocalLifeConfiguration config;private final LocalAccounts accounts;private final LocalShops shops;private final LocalMedia media;
    public LocalCommunity(JdbcTemplate db,Transactions tx,ObjectMapper json,LocalLifeConfiguration config,LocalAccounts accounts,LocalShops shops,LocalMedia media){d=new LocalData(db,tx,json);this.config=config;this.accounts=accounts;this.shops=shops;this.media=media;}
    private static final String REVIEWS="""
        SELECT r.*,a.display_name,p.avatar_id,s.name shop_name,l.cover_path,
          (SELECT COUNT(*) FROM ux_review_like k WHERE k.review_id=r.id) like_count,
          (SELECT COUNT(*) FROM ux_user_coupon c WHERE c.user_id=r.user_id AND c.shop_id=r.shop_id AND c.state='USED') verified_count,
          (SELECT COUNT(*) FROM ux_shop_checkin c WHERE c.user_id=r.user_id AND c.shop_id=r.shop_id) checkin_count
        FROM ux_review r JOIN ux_account a ON a.id=r.user_id LEFT JOIN ux_local_profile p ON p.user_id=r.user_id
         JOIN ux_shop s ON s.id=r.shop_id JOIN ux_local_shop l ON l.shop_id=r.shop_id
        """;
    private Map<String,Object> view(Map<String,Object> row,Long viewer){row.put("mediaIds",d.strings(row.remove("mediaJson")));row.put("liked",viewer!=null&&d.db.queryForObject("SELECT COUNT(*) FROM ux_review_like WHERE review_id=? AND user_id=?",Integer.class,row.get("id"),viewer)>0);
        row.put("own",viewer!=null&&viewer==LocalData.number(row.get("userId")));row.put("verified",((Number)row.remove("verifiedCount")).intValue()>0);row.put("checkedIn",((Number)row.remove("checkinCount")).intValue()>0);return row;}
    public Object reviews(Long shop,Long owner,Long viewer,int page,String sort){config.require();if(page<1||page>500||!Set.of("latest","popular").contains(sort))throw new Problem(400,"LOCAL_INVALID_INPUT");
        List<Object> args=new ArrayList<>();String where=owner==null?" WHERE r.state='PUBLISHED' AND l.status='PUBLISHED'":" WHERE r.user_id=? AND r.state<>'DELETED'";if(owner!=null){if(!owner.equals(viewer))throw new Problem(404,"LOCAL_NOT_FOUND");args.add(owner);}if(shop!=null){where+=" AND r.shop_id=?";args.add(shop);}
        String order="popular".equals(sort)?"(SELECT COUNT(*) FROM ux_review_like z WHERE z.review_id=r.id AND z.created_at>=?) DESC,r.created_at DESC,r.id":"r.created_at DESC,r.id";
        int total=d.db.queryForObject("SELECT COUNT(*) FROM ux_review r JOIN ux_local_shop l ON l.shop_id=r.shop_id"+where,Integer.class,args.toArray());
        if(sort.equals("popular"))args.add(LocalData.at(d.now().minusSeconds(7*86400)));args.add((page-1)*12);
        var rows=d.rows(REVIEWS+where+" ORDER BY "+order+" LIMIT 12 OFFSET ?",args.toArray());rows.forEach(r->view(r,viewer));return Map.of("items",rows,"total",total,"page",page,"pageSize",12);
    }
    public Map<String,Object> detail(String id,Long viewer){config.require();var row=d.one(REVIEWS+" WHERE r.id=?",id);boolean own=viewer!=null&&viewer==LocalData.number(row.get("userId"));if("DELETED".equals(row.get("state"))||(!"PUBLISHED".equals(row.get("state"))&&!own))throw new Problem(404,"LOCAL_NOT_FOUND");return view(row,viewer);}
    private List<String> images(Object value,int max){if(value==null)return List.of();if(!(value instanceof List<?> list)||list.size()>max)throw new Problem(400,"LOCAL_INVALID_IMAGE");return list.stream().map(v->LocalData.text(v,36,36)).toList();}
    public Object review(long user,long shop,String id,Map<String,Object> body){config.require();accounts.limit("review:"+user,10);String text=LocalData.text(body.get("body"),10,1000);int rating=LocalData.integer(body.get("rating"),1,5);var pictures=images(body.get("mediaIds"),6);
        return d.tx.run(()->{d.lockUser(user);shops.published(shop);String target=id;
            if(id==null){var prior=d.rows("SELECT * FROM ux_review WHERE user_id=? AND shop_id=? AND active_slot=1",user,shop);if(!prior.isEmpty()){
                var existing=prior.get(0);if(existing.get("body").equals(text)&&((Number)existing.get("rating")).intValue()==rating&&d.strings(existing.get("mediaJson")).equals(pictures))return detail(existing.get("id").toString(),user);throw new Problem(409,"REVIEW_EXISTS");}
                target=LocalData.id();d.db.update("INSERT INTO ux_review(id,user_id,shop_id,rating,body,media_json,state,active_slot,created_at,updated_at) VALUES(?,?,?,?,?,?,'PUBLISHED',1,?,?)",target,user,shop,rating,text,d.encode(pictures),LocalData.at(d.now()),LocalData.at(d.now()));
            }else{var old=d.one("SELECT * FROM ux_review WHERE id=? AND user_id=? AND shop_id=? FOR UPDATE",id,user,shop);if("DELETED".equals(old.get("state"))||((Number)old.get("version")).longValue()!=LocalData.version(body))throw new Problem(409,"LOCAL_VERSION_CONFLICT");
                d.db.update("UPDATE ux_review SET rating=?,body=?,media_json=?,version=version+1,updated_at=? WHERE id=?",rating,text,d.encode(pictures),LocalData.at(d.now()),id);}
            media.attach(user,"REVIEW",target,pictures);return detail(target,user);});
    }
    public Object deleteReview(long user,String id,long version){config.require();return d.tx.run(()->{d.lockUser(user);var old=d.one("SELECT * FROM ux_review WHERE id=? AND user_id=? FOR UPDATE",id,user);if("DELETED".equals(old.get("state")))return Map.of("deleted",true);if(((Number)old.get("version")).longValue()!=version)throw new Problem(409,"LOCAL_VERSION_CONFLICT");
        d.db.update("UPDATE ux_review SET state='DELETED',active_slot=NULL,version=version+1,updated_at=? WHERE id=?",LocalData.at(d.now()),id);media.attach(user,"REVIEW",id,List.of());return Map.of("deleted",true);});}
    public Object like(long user,String id,boolean desired){config.require();return d.tx.run(()->{d.lockUser(user);var row=d.one("SELECT user_id,state FROM ux_review WHERE id=? FOR UPDATE",id);if(!"PUBLISHED".equals(row.get("state")))throw new Problem(404,"LOCAL_NOT_FOUND");if(user==LocalData.number(row.get("userId")))throw new Problem(409,"SELF_LIKE_FORBIDDEN");
        if(desired){if(d.db.queryForObject("SELECT COUNT(*) FROM ux_review_like WHERE user_id=? AND review_id=?",Integer.class,user,id)==0)d.db.update("INSERT INTO ux_review_like VALUES(?,?,?)",user,id,LocalData.at(d.now()));}else d.db.update("DELETE FROM ux_review_like WHERE user_id=? AND review_id=?",user,id);
        return Map.of("liked",desired,"likeCount",d.db.queryForObject("SELECT COUNT(*) FROM ux_review_like WHERE review_id=?",Integer.class,id));});}
    public Map<String,Object> signs(long user){config.require();LocalDate today=d.now().atZone(LocalData.ZONE).toLocalDate();Set<LocalDate> dates=new HashSet<>(d.db.query("SELECT sign_date FROM ux_sign_record WHERE user_id=?",(r,n)->r.getDate(1).toLocalDate(),user));int streak=streak(dates,today);var month=dates.stream().filter(x->YearMonth.from(x).equals(YearMonth.from(today))).sorted().toList();
        return Map.of("today",today,"signed",dates.contains(today),"dates",month,"streak",streak,"total",dates.size());}
    static int streak(Set<LocalDate> dates,LocalDate today){int count=0;LocalDate day=dates.contains(today)?today:today.minusDays(1);while(dates.contains(day)){count++;day=day.minusDays(1);}return count;}
    public Object sign(long user){config.require();return d.tx.run(()->{d.lockUser(user);LocalDate day=d.now().atZone(LocalData.ZONE).toLocalDate();if(d.db.queryForObject("SELECT COUNT(*) FROM ux_sign_record WHERE user_id=? AND sign_date=?",Integer.class,user,java.sql.Date.valueOf(day))==0)d.db.update("INSERT INTO ux_sign_record VALUES(?,?,?)",user,java.sql.Date.valueOf(day),LocalData.at(d.now()));return signs(user);});}
    public Object checkin(long user,long shop,Map<String,Object> b){config.require();String note=LocalData.text(b.get("note"),0,200);var pictures=images(b.get("mediaIds"),3);
        return d.tx.run(()->{d.lockUser(user);shops.published(shop);LocalDate day=d.now().atZone(LocalData.ZONE).toLocalDate();var old=d.rows("SELECT * FROM ux_shop_checkin WHERE user_id=? AND shop_id=? AND checkin_date=?",user,shop,java.sql.Date.valueOf(day));if(!old.isEmpty())return old.get(0);
            String id=LocalData.id();d.db.update("INSERT INTO ux_shop_checkin VALUES(?,?,?,?,?,?,?)",id,user,shop,java.sql.Date.valueOf(day),note,d.encode(pictures),LocalData.at(d.now()));media.attach(user,"CHECKIN",id,pictures);return Map.of("id",id,"shopId",Long.toString(shop),"date",day,"note",note,"message","探店记录已保存；这是自主记录，不代表已验证到店。");});}
    public Object checkins(long user){config.require();var rows=d.rows("SELECT c.*,s.name shop_name FROM ux_shop_checkin c JOIN ux_shop s ON s.id=c.shop_id WHERE c.user_id=? ORDER BY c.created_at DESC LIMIT 100",user);rows.forEach(r->r.put("mediaIds",d.strings(r.remove("mediaJson"))));return Map.of("items",rows);}
    public void deleteCheckin(long user,String id){config.require();d.tx.run(()->{d.lockUser(user);d.one("SELECT id FROM ux_shop_checkin WHERE id=? AND user_id=? FOR UPDATE",id,user);d.db.update("DELETE FROM ux_shop_checkin WHERE id=?",id);media.attach(user,"CHECKIN",id,List.of());return null;});}
    public Object report(long user,String review,String reason){config.require();String text=LocalData.text(reason,2,300);return d.tx.run(()->{d.lockUser(user);detail(review,user);var old=d.rows("SELECT id,state FROM ux_content_report WHERE user_id=? AND review_id=?",user,review);if(!old.isEmpty())return old.get(0);String id=LocalData.id();d.db.update("INSERT INTO ux_content_report(id,user_id,review_id,reason,state,created_at,updated_at) VALUES(?,?,?,?,'OPEN',?,?)",id,user,review,text,LocalData.at(d.now()),LocalData.at(d.now()));return Map.of("id",id,"state","OPEN");});}
    public Object moderate(long operator,String id,Map<String,Object> body){accounts.requireOperator(operator);String state=LocalData.text(body.get("state"),1,16),reason=LocalData.text(body.get("reason"),5,300);if(!Set.of("HIDDEN","PUBLISHED").contains(state))throw new Problem(400,"LOCAL_INVALID_INPUT");return d.tx.run(()->{
        var row=d.one("SELECT * FROM ux_review WHERE id=? FOR UPDATE",id);if("DELETED".equals(row.get("state"))||((Number)row.get("version")).longValue()!=LocalData.version(body))throw new Problem(409,"LOCAL_VERSION_CONFLICT");
        d.db.update("UPDATE ux_review SET state=?,moderation_reason=?,version=version+1,updated_at=? WHERE id=?",state,reason,LocalData.at(d.now()),id);d.audit(operator,"REVIEW_MODERATE",id,state+" "+reason);return Map.of("id",id,"state",state,"version",((Number)row.get("version")).longValue()+1);});}
    public Object opsReviews(long operator){accounts.requireOperator(operator);var rows=d.rows(REVIEWS+" WHERE r.state<>'DELETED' ORDER BY r.updated_at DESC LIMIT 100");rows.forEach(r->view(r,operator));return Map.of("items",rows);}
}
