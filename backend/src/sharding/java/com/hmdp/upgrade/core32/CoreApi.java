package com.hmdp.upgrade.core32;

import com.hmdp.upgrade.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController @RequestMapping("/v2")
@ConditionalOnProperty(name="upgrade.core32.enabled",havingValue="true")
public class CoreApi {
    private final CoreEngine engine;private final CoreQueries queries;private final CoreJobs jobs;private final CoreWaitlists waitlists;private final ShopCache shops;
    @Value("${upgrade.benchmark:false}")private boolean benchmark;
    @Value("${upgrade.sandbox-payments:false}")private boolean sandbox;
    public CoreApi(CoreEngine engine,CoreQueries queries,CoreJobs jobs,CoreWaitlists waitlists,ShopCache shops){this.engine=engine;this.queries=queries;this.jobs=jobs;this.waitlists=waitlists;this.shops=shops;}
    private long user(HttpServletRequest request){return ((Auth.Identity)request.getAttribute("identity")).user();}
    public record Purchase(String requestId,long activityId){}
    @PostMapping("/requests")public Object purchase(HttpServletRequest request,@RequestBody Purchase body){var accepted=engine.purchase(user(request),body.requestId(),body.activityId());return ResponseEntity.status(accepted.state().equals("ACCEPTED")?202:200).body(accepted);}
    @GetMapping("/requests/{id}")public Object result(HttpServletRequest request,@PathVariable String id){return engine.response(engine.request(user(request),id));}
    @GetMapping("/requests/{id}/order")public Object order(HttpServletRequest request,@PathVariable String id){return view(engine.order(user(request),id));}
    @PostMapping("/requests/{id}/{action:confirm|cancel}")public Object transition(HttpServletRequest request,@PathVariable String id,@PathVariable String action){engine.requireCore();return view(engine.transition(user(request),id,action));}
    @GetMapping("/account/requests")public Object requests(HttpServletRequest request,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="")String state){return queries.list(user(request),page,state);}
    @GetMapping("/account/requests/by-key/{key}")public Object byKey(HttpServletRequest request,@PathVariable String key){return queries.detail(user(request),key,true);}
    @GetMapping("/account/requests/{id}")public Object detail(HttpServletRequest request,@PathVariable String id){return queries.detail(user(request),id,false);}
    @GetMapping("/account/order-history")public Object history(HttpServletRequest request,@RequestParam(defaultValue="1")int page){return queries.history(user(request),page);}
    public record Payment(String requestId){}
    public record Receipt(String channelId,int amountCents){}
    private void paymentEnabled(){if(!sandbox)throw new Problem(404,"SANDBOX_PAYMENTS_DISABLED");}
    @PostMapping("/account/payments")public Object payment(HttpServletRequest request,@RequestBody Payment body){paymentEnabled();engine.requireCore();return view(engine.createPayment(user(request),body.requestId()));}
    @GetMapping("/account/payments/{id}")public Object payment(HttpServletRequest request,@PathVariable String id){paymentEnabled();return view(engine.payment(user(request),id));}
    @PostMapping("/account/payments/{id}/sandbox-receipt")public Object receipt(HttpServletRequest request,@PathVariable String id,@RequestBody Receipt body){paymentEnabled();engine.requireCore();return view(engine.receipt(user(request),id,body.channelId(),body.amountCents()));}
    public record Wait(long activityId){}
    @PostMapping("/waitlists")public Object waitlist(HttpServletRequest request,@RequestBody Wait body){engine.requireCore();return waitlists.join(user(request),body.activityId());}
    @GetMapping("/account/waitlists")public Object waitlists(HttpServletRequest request){return waitlists.list(user(request));}
    @PostMapping("/waitlists/{id}/cancel")public Object cancelWaitlist(HttpServletRequest request,@PathVariable String id){engine.requireCore();return waitlists.cancel(user(request),id);}
    @GetMapping("/shops/{id}")public Object shop(@PathVariable long id){var shop=shops.get(id);if(shop==null)throw new Problem(404,"SHOP_NOT_FOUND");return shop;}
    public record Shop(String name,String description){}
    @PutMapping("/admin/shops/{id}")public Object shop(@PathVariable long id,@RequestBody Shop body){return shops.save(id,body.name(),body.description());}
    @GetMapping({"/admin/status","/admin/pipeline","/admin/core32"})public Object status(){return queries.status();}
    @GetMapping("/admin/core32/orders/{id}")public Object byOrder(@PathVariable String id){return view(queries.byOrder(id));}
    @GetMapping("/admin/core32/logs/{id}")public Object logs(@PathVariable String id){return engine.db.queryForList("SELECT * FROM cx_check_log WHERE order_id=? ORDER BY created_at,id",id);}
    public record Mode(String mode){}
    @PostMapping("/admin/core32/mode")public Object mode(@RequestBody Mode body){
        if(body.mode()==null||!List.of("CORE","PAUSED").contains(body.mode()))throw new Problem(400,"INVALID_CORE_MODE");
        if(body.mode().equals("CORE")&&engine.stock.db.queryForObject("SELECT COUNT(*) FROM ux_core_migration WHERE name='activated' AND done=TRUE",Integer.class)==0)throw new Problem(409,"CORE_MIGRATION_REQUIRED");
        if(body.mode().equals("CORE")&&engine.stock.db.queryForObject("SELECT COUNT(*) FROM ux_core_migration WHERE name LIKE 'recovery_%' AND done=FALSE",Integer.class)>0)throw new Problem(409,"CORE_RECOVERY_INCOMPLETE");
        engine.stock.db.update("UPDATE ux_core_control SET mode=?,changed_at=CURRENT_TIMESTAMP(3) WHERE id=1",body.mode());return Map.of("mode",body.mode());
    }
    @PostMapping("/admin/fixtures/{id}")public Object fixture(@PathVariable long id,@RequestParam(defaultValue="100")int stock){
        if(!benchmark||id<100000)throw new Problem(404,"NOT_FOUND");if(stock<0||stock>1000000)throw new Problem(400,"INVALID_STOCK");engine.requireCore();
        engine.stock.db.update("INSERT INTO ux_activity(id,capacity,available,price_cents,starts_at,ends_at,process_until) VALUES(?,?,?,990,TIMESTAMPADD(SECOND,-60,CURRENT_TIMESTAMP(3)),TIMESTAMPADD(HOUR,2,CURRENT_TIMESTAMP(3)),TIMESTAMPADD(HOUR,3,CURRENT_TIMESTAMP(3)))",id,stock,stock);
        shops.save(id,"校园活动 "+id,"趣票核心分片独立测试数据");engine.stock.db.update("INSERT INTO ux_offer(activity_id,shop_id,title,face_value_cents,terms) VALUES(?,?,'测试学生票',990,'独立环境模拟票档')",id,id);engine.prepare(id);jobs.catalog();return Map.of("id",id,"stock",stock);
    }
    @GetMapping("/admin/fixtures/{id}/reservation")public Object reservation(@PathVariable long id){if(!benchmark||id<100000)throw new Problem(404,"NOT_FOUND");return Map.of("remaining",engine.redis.remaining(id));}
    static Map<String,Object> view(Map<String,Object> row){var copy=new LinkedHashMap<String,Object>(row);if(copy.get("user_id")!=null)copy.put("user_id",copy.get("user_id").toString());return copy;}
}
