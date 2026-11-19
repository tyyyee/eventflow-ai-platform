package com.hmdp.upgrade;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LocalShops {
    final LocalData data;private final LocalLifeConfiguration config;private final LocalAccounts accounts;
    @Autowired(required=false) private ShopBloom bloom;
    @Autowired(required=false) private ShopCache cache;
    @Autowired private LocalMedia media;
    @Autowired(required=false) private Storefront storefront;
    public LocalShops(JdbcTemplate db,Transactions tx,ObjectMapper json,LocalLifeConfiguration config,LocalAccounts accounts){data=new LocalData(db,tx,json);this.config=config;this.accounts=accounts;}
    public static final List<String> CATEGORIES=List.of("美食","咖啡茶饮","火锅","甜品烘焙","休闲娱乐","运动体验");
    public static final List<Map<String,Object>> LANDMARKS=List.of(
        Map.of("id","main-east","name","主校区东门","campus","主校区","latitude",31.030,"longitude",121.442),
        Map.of("id","main-library","name","主校区图书馆","campus","主校区","latitude",31.035,"longitude",121.434),
        Map.of("id","east-gate","name","东校区南门","campus","东校区","latitude",31.049,"longitude",121.463),
        Map.of("id","east-hall","name","东校区体育馆","campus","东校区","latitude",31.052,"longitude",121.459),
        Map.of("id","south-gate","name","南校区北门","campus","南校区","latitude",31.013,"longitude",121.425),
        Map.of("id","south-art","name","南校区艺术楼","campus","南校区","latitude",31.009,"longitude",121.431));
    private static final String SELECT="""
        SELECT s.id,s.name,s.description,p.*,
         (SELECT COUNT(*) FROM ux_review r WHERE r.shop_id=s.id AND r.state='PUBLISHED') review_count,
         (SELECT AVG(r.rating) FROM ux_review r WHERE r.shop_id=s.id AND r.state='PUBLISHED') rating
        FROM ux_shop s JOIN ux_local_shop p ON p.shop_id=s.id
        """;
    public void published(long shop){config.require();if(data.db.queryForObject("SELECT COUNT(*) FROM ux_local_shop WHERE shop_id=? AND status='PUBLISHED'",Integer.class,shop)==0)throw new Problem(404,"LOCAL_NOT_FOUND");}
    private Map<String,Object> decorate(Map<String,Object> shop,Long user,Instant now,Double lat,Double lon){
        shop.put("tags",data.strings(shop.remove("tagsJson")));shop.put("photos",data.strings(shop.remove("photosJson")));
        Object hours=shop.remove("hoursJson"),closed=shop.remove("closedDatesJson");
        try{shop.put("hours",data.json.readTree(hours.toString()));}catch(Exception e){throw new Problem(500,"LOCAL_DATA_INVALID");}
        shop.put("closedDates",data.strings(closed));shop.put("open",isOpen(hours.toString(),data.strings(closed),now));
        Object rating=shop.get("rating");if(rating instanceof Number n)shop.put("rating",Math.round(n.doubleValue()*10)/10.0);
        long id=LocalData.number(shop.get("id"));
        shop.put("favorite",user!=null&&data.db.queryForObject("SELECT COUNT(*) FROM ux_favorite WHERE user_id=? AND target_type='SHOP' AND target_id=?",Integer.class,user,id)>0);
        shop.put("distanceMeters",lat!=null&&lon!=null&&shop.get("latitude") instanceof Number a&&shop.get("longitude") instanceof Number b?distance(lat,lon,a.doubleValue(),b.doubleValue()):null);
        shop.put("checkedAt",now);shop.put("sourcePath","/v2/local/shops/"+id);return shop;
    }
    public boolean isOpen(String hours,List<String> closed,Instant now){
        var local=now.atZone(LocalData.ZONE);if(closed.contains(local.toLocalDate().toString()))return false;
        try{for(JsonNode period:data.json.readTree(hours)){
            int day=period.path("day").asInt();LocalTime start=LocalTime.parse(period.path("start").asText()),end=LocalTime.parse(period.path("end").asText());
            int current=local.getDayOfWeek().getValue();LocalTime time=local.toLocalTime();
            if(start.isBefore(end)){if(current==day&&!time.isBefore(start)&&time.isBefore(end))return true;}
            else if((current==day&&!time.isBefore(start))||(current==day%7+1&&time.isBefore(end)&&!closed.contains(local.toLocalDate().minusDays(1).toString())))return true;
        }return false;}catch(Exception e){return false;}
    }
    static int distance(double a,double b,double c,double d){double x=Math.toRadians(c-a),y=Math.toRadians(d-b);double q=Math.sin(x/2)*Math.sin(x/2)+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(c))*Math.sin(y/2)*Math.sin(y/2);return (int)Math.round(6371000*2*Math.atan2(Math.sqrt(q),Math.sqrt(Math.max(0,1-q))));}
    private Double coordinate(String value,double min,double max){if(value==null||value.isBlank())return null;try{double n=Double.parseDouble(value);if(!Double.isFinite(n)||n<min||n>max)throw new Exception();return n;}catch(Exception e){throw new Problem(400,"LOCAL_INVALID_INPUT");}}
    public Map<String,Object> list(Map<String,String> f,Long user){config.require();String q=LocalData.text(f.get("q"),0,80),category=LocalData.text(f.get("category"),0,24),campus=LocalData.text(f.get("campus"),0,24),area=LocalData.text(f.get("area"),0,60);
        int page=f.containsKey("page")?LocalData.integer(f.get("page"),1,500):1;String sort=f.getOrDefault("sort","recommended");if(!List.of("recommended","rating","price","distance").contains(sort))throw new Problem(400,"LOCAL_INVALID_INPUT");
        Double lat=coordinate(f.get("latitude"),-90,90),lon=coordinate(f.get("longitude"),-180,180);
        if((lat==null)!=(lon==null))throw new Problem(400,"LOCAL_INVALID_INPUT");
        if(lat==null){String landmark=f.getOrDefault("landmark","main-east");var point=LANDMARKS.stream().filter(x->x.get("id").equals(landmark)).findFirst().orElseThrow(()->new Problem(400,"LOCAL_INVALID_INPUT"));lat=((Number)point.get("latitude")).doubleValue();lon=((Number)point.get("longitude")).doubleValue();}
        int min=f.containsKey("minPriceCents")?LocalData.integer(f.get("minPriceCents"),0,1000000):0,max=f.containsKey("maxPriceCents")?LocalData.integer(f.get("maxPriceCents"),0,1000000):1000000;
        if(min>max)throw new Problem(400,"LOCAL_INVALID_INPUT");double score=f.containsKey("minRating")?Optional.ofNullable(coordinate(f.get("minRating"),0,5)).orElse(0.0):0;
        Integer radius=f.containsKey("maxDistanceMeters")?LocalData.integer(f.get("maxDistanceMeters"),1,100000):null;
        String pattern="%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        var rows=data.rows(SELECT+" WHERE p.status='PUBLISHED' AND (s.name LIKE ? ESCAPE '!' OR s.description LIKE ? ESCAPE '!' OR p.tags_json LIKE ? ESCAPE '!' OR p.category LIKE ? ESCAPE '!') AND (?='' OR p.category=?) AND (?='' OR p.campus=?) AND (?='' OR p.area=?) LIMIT 501",pattern,pattern,pattern,pattern,category,category,campus,campus,area,area);
        if(rows.size()>500)throw new Problem(400,"LOCAL_SEARCH_TOO_BROAD");Instant now=data.now();List<Map<String,Object>> found=new ArrayList<>();
        for(var row:rows){var shop=decorate(row,user,now,lat,lon);Object price=shop.get("avgPriceCents");if((min>0||max<1000000)&&(!(price instanceof Number n)||n.intValue()<min||n.intValue()>max))continue;
            if(score>0&&(!(shop.get("rating") instanceof Number n)||n.doubleValue()<score))continue;
            if("true".equals(f.get("openOnly"))&&!Boolean.TRUE.equals(shop.get("open")))continue;
            if(radius!=null&&(!(shop.get("distanceMeters") instanceof Number n)||n.intValue()>radius))continue;
            if("true".equals(f.get("hasCoupon"))&&data.db.queryForObject("SELECT COUNT(*) FROM ux_coupon_template WHERE shop_id=? AND state='PUBLISHED' AND remaining>0 AND claim_from<=? AND claim_until>?",Integer.class,LocalData.number(shop.get("id")),LocalData.at(now),LocalData.at(now))==0)continue;
            found.add(shop);
        }
        Comparator<Map<String,Object>> order=switch(sort){
            case "price" -> Comparator.comparingDouble(x->num(x,"avgPriceCents",Double.MAX_VALUE));
            case "distance" -> Comparator.comparingDouble(x->num(x,"distanceMeters",Double.MAX_VALUE));
            case "rating" -> Comparator.<Map<String,Object>>comparingDouble(x->num(x,"rating",-1)).reversed().thenComparing(Comparator.<Map<String,Object>>comparingDouble(x->num(x,"reviewCount",0)).reversed());
            default -> Comparator.<Map<String,Object>>comparingDouble(x->num(x,"priority",0)).reversed().thenComparing(Comparator.<Map<String,Object>>comparingDouble(x->num(x,"reviewCount",0)).reversed()).thenComparing(Comparator.<Map<String,Object>>comparingDouble(x->num(x,"rating",-1)).reversed());
        };
        found.sort(order.thenComparing(x->LocalData.number(x.get("id"))));int start=Math.min((page-1)*12,found.size());
        return Map.of("items",found.subList(start,Math.min(start+12,found.size())),"total",found.size(),"page",page,"pageSize",12,"categories",CATEGORIES,"landmarks",LANDMARKS,"serverTime",now,"distanceType","straight-line");
    }
    private static double num(Map<String,Object> row,String key,double fallback){return row.get(key) instanceof Number n?n.doubleValue():fallback;}
    public Map<String,Object> detail(long id,Long user){config.require();var row=data.one(SELECT+" WHERE s.id=?",id);
        if(!"PUBLISHED".equals(row.get("status"))){return Map.of("id",Long.toString(id),"name",row.get("name"),"status",row.get("status"),"unavailable",true);}
        var point=LANDMARKS.get(0);var result=decorate(row,user,data.now(),((Number)point.get("latitude")).doubleValue(),((Number)point.get("longitude")).doubleValue());
        if(cache!=null){var basic=cache.get(id);if(basic!=null){result.put("name",basic.name());result.put("description",basic.description());}}
        return result;
    }
    public Object favorites(long user){config.require();var result=new ArrayList<Map<String,Object>>();
        for(var f:data.rows("SELECT * FROM ux_favorite WHERE user_id=? ORDER BY created_at DESC LIMIT 500",user)){
            long id=LocalData.number(f.get("targetId"));if("SHOP".equals(f.get("targetType"))){try{var shop=detail(id,user);f.put("target",shop);}catch(Problem missing){f.put("target",Map.of("id",Long.toString(id),"name","内容已下架","unavailable",true));}}
            else{try{if(storefront==null)throw new Problem(404,"LOCAL_NOT_FOUND");f.put("target",storefront.detail(id).shop());}catch(Problem missing){f.put("target",Map.of("id",Long.toString(id),"name","活动已下架","unavailable",true));}}result.add(f);
        }return Map.of("items",result);
    }
    public Object favorite(long user,String type,long id,boolean desired){config.require();if(!Set.of("SHOP","EVENT").contains(type))throw new Problem(400,"LOCAL_INVALID_INPUT");
        return data.tx.run(()->{data.lockUser(user);
            if(desired){if(type.equals("SHOP"))published(id);else if(data.db.queryForObject("SELECT COUNT(*) FROM ux_storefront WHERE shop_id=? AND published=TRUE",Integer.class,id)==0)throw new Problem(404,"LOCAL_NOT_FOUND");
                if(data.db.queryForObject("SELECT COUNT(*) FROM ux_favorite WHERE user_id=? AND target_type=? AND target_id=?",Integer.class,user,type,id)==0){
                    if(data.db.queryForObject("SELECT COUNT(*) FROM ux_favorite WHERE user_id=?",Integer.class,user)>=500)throw new Problem(409,"FAVORITE_LIMIT");
                    data.db.update("INSERT INTO ux_favorite VALUES(?,?,?,?)",user,type,id,LocalData.at(data.now()));}}
            else data.db.update("DELETE FROM ux_favorite WHERE user_id=? AND target_type=? AND target_id=?",user,type,id);
            return Map.of("favorite",desired,"targetId",Long.toString(id),"targetType",type);});
    }
    public Object opsList(long user){accounts.requireOperator(user);return Map.of("items",data.rows(SELECT+" ORDER BY s.id LIMIT 200"));}
    public Object save(long user,Long id,Map<String,Object> b){accounts.requireOperator(user);
        String name=LocalData.text(b.get("name"),1,100),description=LocalData.text(b.get("description"),10,2000),category=LocalData.text(b.get("category"),1,24),campus=LocalData.text(b.get("campus"),1,24),area=LocalData.text(b.get("area"),1,60),address=LocalData.text(b.get("address"),1,200);
        if(!CATEGORIES.contains(category)||!Set.of("主校区","东校区","南校区").contains(campus))throw new Problem(400,"LOCAL_INVALID_INPUT");
        String status=LocalData.text(b.getOrDefault("status","DRAFT"),1,16);if(!Set.of("DRAFT","PUBLISHED","CLOSED").contains(status))throw new Problem(400,"LOCAL_INVALID_INPUT");
        String cover=asset(b.get("coverPath"));List<String> photos=list(b.get("photos"),3,12).stream().map(this::asset).toList(),tags=list(b.get("tags"),0,8),closed=list(b.getOrDefault("closedDates",List.of()),0,100);try{closed.forEach(LocalDate::parse);}catch(Exception e){throw new Problem(400,"LOCAL_INVALID_INPUT");}
        JsonNode hours=data.json.valueToTree(b.get("hours"));if(!hours.isArray()||hours.isEmpty()||hours.size()>21)throw new Problem(400,"LOCAL_INVALID_INPUT");
        try{for(JsonNode h:hours){int day=h.path("day").asInt();if(day<1||day>7)throw new Exception();LocalTime.parse(h.path("start").asText());LocalTime.parse(h.path("end").asText());}}catch(Exception e){throw new Problem(400,"LOCAL_INVALID_INPUT");}
        Integer price=b.get("avgPriceCents")==null?null:LocalData.integer(b.get("avgPriceCents"),0,1000000);
        Double lat=coordinate(Objects.toString(b.get("latitude"),null),-90,90),lon=coordinate(Objects.toString(b.get("longitude"),null),-180,180);if((lat==null)!=(lon==null))throw new Problem(400,"LOCAL_INVALID_INPUT");
        long shop=id==null?ThreadLocalRandom.current().nextLong(100000,1000000000):id;

        data.tx.run(()->{if(id==null){if(bloom!=null)bloom.beforeInsert(shop);data.db.update("INSERT INTO ux_shop VALUES(?,?,?,1)",shop,name,description);
            data.db.update("INSERT INTO ux_local_shop(shop_id,category,campus,area,address,latitude,longitude,avg_price_cents,hours_json,closed_dates_json,tags_json,photos_json,cover_path,status,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",shop,category,campus,area,address,lat,lon,price,hours.toString(),data.encode(closed),data.encode(tags),data.encode(photos),cover,status,LocalData.at(data.now()));}
            else{int changed=data.db.update("UPDATE ux_local_shop SET category=?,campus=?,area=?,address=?,latitude=?,longitude=?,avg_price_cents=?,hours_json=?,closed_dates_json=?,tags_json=?,photos_json=?,cover_path=?,status=?,version=version+1,updated_at=? WHERE shop_id=? AND version=?",category,campus,area,address,lat,lon,price,hours.toString(),data.encode(closed),data.encode(tags),data.encode(photos),cover,status,LocalData.at(data.now()),shop,LocalData.version(b));if(changed!=1)throw new Problem(409,"LOCAL_VERSION_CONFLICT");
                data.db.update("UPDATE ux_shop SET name=?,description=?,revision=revision+1 WHERE id=?",name,description,shop);}
            var images=new ArrayList<String>(photos);images.add(cover);media.attach(user,"SHOP",Long.toString(shop),images.stream().filter(x->x.startsWith("/v2/media/")).map(x->x.substring(10)).distinct().toList());
            String key="ux:shop:{"+shop+"}";data.db.update("INSERT INTO ux_invalidation(cache_key,generation,created_at) VALUES(?,?,?) ON DUPLICATE KEY UPDATE generation=VALUES(generation),created_at=VALUES(created_at)",key,LocalData.id(),LocalData.at(data.now()));
            data.audit(user,"SHOP_SAVE",Long.toString(shop),status+" "+name);return null;});return detail(shop,user);
    }
    private List<String> list(Object value,int min,int max){if(!(value instanceof List<?> values)||values.size()<min||values.size()>max)throw new Problem(400,"LOCAL_INVALID_INPUT");return values.stream().map(x->LocalData.text(x,1,250)).toList();}
    private String asset(Object value){String s=LocalData.text(value,1,250);if(!s.matches("/v2/media/[a-f0-9-]{36}")&&!s.matches("/(?:life-assets|local-assets|event-assets)/[A-Za-z0-9_-]+\\.(?:jpg|jpeg|png|webp|svg)"))throw new Problem(400,"LOCAL_INVALID_IMAGE");return s;}
}
