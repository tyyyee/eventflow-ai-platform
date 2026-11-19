package com.hmdp.upgrade.assistant;

import com.hmdp.upgrade.Problem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.hmdp.upgrade.assistant.AssistantData.*;

@Service
public class AssistantCatalog {
    private final JdbcTemplate db;
    public AssistantCatalog(JdbcTemplate source) {
        db=new JdbcTemplate(Objects.requireNonNull(source.getDataSource())); db.setQueryTimeout(2);
    }
    public Instant now() { return db.queryForObject("SELECT CURRENT_TIMESTAMP(3)",Timestamp.class).toInstant(); }
    private static final String SELECT="""
        SELECT a.id activity_id,e.id session_id,s.id shop_id,s.name,e.title session_title,p.category,p.area,p.address,
          e.starts_at event_start,e.ends_at event_end,a.price_cents,a.available,a.starts_at sale_start,a.ends_at sale_end
        FROM ux_experience_session e JOIN ux_activity a ON a.id=e.activity_id
          JOIN ux_shop s ON s.id=e.shop_id JOIN ux_storefront p ON p.shop_id=s.id
          JOIN ux_offer o ON o.activity_id=a.id AND o.shop_id=s.id
        WHERE e.published=TRUE AND p.published=TRUE
        """;
    private Activity activity(ResultSet r,Instant checked) throws SQLException {
        Instant saleStart=r.getTimestamp("sale_start").toInstant(),saleEnd=r.getTimestamp("sale_end").toInstant();
        int available=r.getInt("available");
        String status=!checked.isBefore(saleEnd)?"ENDED":checked.isBefore(saleStart)?"NOT_STARTED":available<=0?"SOLD_OUT":"AVAILABLE";
        return new Activity(r.getString("activity_id"),r.getString("session_id"),r.getString("shop_id"),r.getString("name"),
            r.getString("session_title"),r.getString("category"),r.getString("area"),r.getString("address"),
            r.getTimestamp("event_start").toInstant(),r.getTimestamp("event_end").toInstant(),r.getInt("price_cents"),available,
            saleStart,saleEnd,status,checked,"/v2/catalog/shops/"+r.getString("shop_id"));
    }
    public static void validate(Criteria c,Instant now) {
        try {
            LocalDate today=now.atZone(ZONE).toLocalDate();
            LocalDate from=c.dateFrom==null?today:LocalDate.parse(c.dateFrom),to=c.dateTo==null?from.plusDays(30):LocalDate.parse(c.dateTo);
            if(to.isBefore(from)||to.isAfter(from.plusDays(30))||from.isAfter(today.plusDays(365))) throw new IllegalArgumentException();
            if(c.maxPriceCents!=null&&(c.maxPriceCents<0||c.maxPriceCents>1_000_000)) throw new IllegalArgumentException();
            if(c.hourFrom!=null&&(c.hourFrom<0||c.hourFrom>23)||c.hourTo!=null&&(c.hourTo<1||c.hourTo>24)
                ||(c.hourFrom!=null&&c.hourTo!=null&&c.hourFrom>=c.hourTo)) throw new IllegalArgumentException();
            for(String value:new String[]{c.campus,c.category,c.query}) if(value!=null&&value.length()>80) throw new IllegalArgumentException();
            if(!List.of("time","price").contains(c.sort)) throw new IllegalArgumentException();
        } catch(RuntimeException invalid) { throw new Problem(400,"ASSISTANT_INVALID_FILTER"); }
    }
    private static String like(String value) { return "%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%"; }
    public List<Activity> search(Criteria c,String exclude) {
        Instant checked=now(); validate(c,checked);
        LocalDate today=checked.atZone(ZONE).toLocalDate(),from=c.dateFrom==null?today:LocalDate.parse(c.dateFrom);
        LocalDate to=c.dateTo==null?from.plusDays(30):LocalDate.parse(c.dateTo);
        StringBuilder sql=new StringBuilder(SELECT).append(" AND e.starts_at>? AND e.starts_at>=? AND e.ends_at<=? AND a.ends_at>? AND a.available>0");
        List<Object> args=new ArrayList<>(List.of(Timestamp.from(checked),Timestamp.from(from.atStartOfDay(ZONE).toInstant()),
            Timestamp.from(to.plusDays(1).atStartOfDay(ZONE).toInstant()),Timestamp.from(checked)));
        if(c.maxPriceCents!=null) { sql.append(" AND a.price_cents<=?"); args.add(c.maxPriceCents); }
        if(c.campus!=null&&!c.campus.isBlank()) { sql.append(" AND p.area LIKE ? ESCAPE '!'"); args.add(like(c.campus)); }
        if(c.category!=null&&!c.category.isBlank()) { sql.append(" AND p.category=?"); args.add(c.category); }
        if(c.query!=null&&!c.query.isBlank()) { sql.append(" AND (s.name LIKE ? ESCAPE '!' OR s.description LIKE ? ESCAPE '!' OR e.tags LIKE ? ESCAPE '!')"); for(int i=0;i<3;i++)args.add(like(c.query)); }
        if(exclude!=null) { sql.append(" AND a.id<>?"); args.add(Long.parseLong(exclude)); }
        sql.append(c.sort.equals("price")?" ORDER BY a.price_cents,e.starts_at,e.id":" ORDER BY e.starts_at,a.price_cents,e.id");
        // Hour constraints use the campus timezone; JDBC timestamps are UTC. Scan is bounded and never silently reports no match after truncation.
        List<Activity> result=new ArrayList<>();
        for(int offset=0;offset<1000;offset+=200) {
            List<Object> page=new ArrayList<>(args); page.add(offset);
            var rows=db.query(sql+" LIMIT 200 OFFSET ?",(r,n)->activity(r,checked),page.toArray());
            for(Activity a:rows) {
                var date=a.startsAt().atZone(ZONE).toLocalDate();
                Instant start=date.atStartOfDay(ZONE).plusHours(c.hourFrom==null?0:c.hourFrom).toInstant();
                Instant end=date.atStartOfDay(ZONE).plusHours(c.hourTo==null?24:c.hourTo).toInstant();
                if(!a.startsAt().isBefore(start)&&!a.endsAt().isAfter(end)) result.add(a);
                if(result.size()==8) return result;
            }
            if(rows.size()<200) return result;
        }
        throw new Problem(400,"ASSISTANT_QUERY_TOO_BROAD");
    }
    public Activity detail(String id) {
        long value; try { value=Long.parseLong(id); if(value<=0)throw new IllegalArgumentException(); }
        catch(RuntimeException invalid) { throw new Problem(400,"ASSISTANT_INVALID_ACTIVITY"); }
        Instant checked=now();
        var rows=db.query(SELECT+" AND a.id=? AND e.starts_at>?",(r,n)->activity(r,checked),value,Timestamp.from(checked));
        if(rows.isEmpty()) throw new Problem(404,"ACTIVITY_NOT_FOUND"); return rows.get(0);
    }
    public List<Rule> rules(String activityId) {
        Instant checked=now();
        List<Rule> result=new ArrayList<>();
        result.add(new Rule(null,"平台购票规则 v1",
            "同一用户对同一活动限购一次，取消或过期后也不能再次购买该活动。订单创建后须在5分钟内完成支付，具体截止时间以订单为准。请求已受理不代表已生成订单。",
            "当前平台用于本地演示，不涉及真实资金。未发布的退改、入场细节请向活动主办方确认。","/v2/assistant/policy",checked));
        if(activityId!=null) {
            Activity activity=detail(activityId);
            var specific=db.query("SELECT o.terms,p.service_notice FROM ux_offer o JOIN ux_storefront p ON p.shop_id=o.shop_id WHERE o.activity_id=? AND p.published=TRUE",
                (r,n)->new Rule(activityId,activity.title(),r.getString(1),r.getString(2),activity.sourcePath(),checked),Long.parseLong(activityId));
            result.addAll(specific);
        }
        return result;
    }
}
