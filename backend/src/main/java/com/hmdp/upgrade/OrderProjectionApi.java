package com.hmdp.upgrade;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="upgrade.core32.enabled",havingValue="false",matchIfMissing=true)
public class OrderProjectionApi {
    public record HistoryListing(java.util.List<OrderProjection.HistoryItem> items,int total,int page,String consistency) {}
    private final OrderProjection projection;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ExternalOrderHistory external;
    public OrderProjectionApi(OrderProjection projection) {this.projection=projection;}
    @GetMapping("/v2/account/order-history")
    public HistoryListing list(HttpServletRequest request,@RequestParam(defaultValue="1") int page) {
        long user=((Auth.Identity)request.getAttribute("identity")).user();
        if(external!=null && external.readsShards())
            return new HistoryListing(external.list(user,page),external.count(user),page,"eventual");
        return new HistoryListing(projection.list(user,page),projection.count(user),page,"eventual");
    }
    @GetMapping("/v2/admin/order-projection") public Map<String,Object> status() {return projection.status();}
    @PostMapping("/v2/admin/order-projection/replay") public Map<String,Object> replay(@RequestParam(defaultValue="") String after) {
        if(after.length()>36) throw new Problem(400,"INVALID_CURSOR");
        return projection.replay(after);
    }
}
