package com.hmdp.upgrade;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="upgrade.core32.enabled",havingValue="false",matchIfMissing=true)
@RequestMapping("/v2/admin/order-sharding")
public class OrderShardingApi {
    private final ExternalOrderHistory history;
    public OrderShardingApi(ExternalOrderHistory history) {this.history=history;}
    @GetMapping public Map<String,Object> status() {return history.status();}
    @PostMapping("/backfill") public Map<String,Object> backfill(@RequestParam(defaultValue="20") int limit) {return history.backfill(limit);}
    @PostMapping("/verify") public Map<String,Object> verify(@RequestParam(defaultValue="20") int limit) {return history.verify(limit);}
    public record ReadMode(String mode) {}
    @PostMapping("/read-mode") public Map<String,Object> readMode(@RequestBody ReadMode input,HttpServletRequest request) {
        history.readMode(input.mode(),((Auth.Identity)request.getAttribute("identity")).user());return history.status();
    }
}
