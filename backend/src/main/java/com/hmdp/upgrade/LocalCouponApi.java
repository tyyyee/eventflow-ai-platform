package com.hmdp.upgrade;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class LocalCouponApi {
    final LocalCoupons service;public LocalCouponApi(LocalCoupons service){this.service=service;}
    @GetMapping("/v2/local/shops/{id}/coupons") public Object list(HttpServletRequest r,@PathVariable long id){return service.templates(id,LocalAccountApi.optionalUser(r));}
    @PostMapping("/v2/coupons/{id}/claim") public Object claim(HttpServletRequest r,@PathVariable String id){return service.claim(LocalAccountApi.user(r),id);}
    @GetMapping("/v2/account/coupons") public Object mine(HttpServletRequest r){return service.wallet(LocalAccountApi.user(r));}
    @GetMapping("/v2/account/coupons/{id}") public Object detail(HttpServletRequest r,@PathVariable String id){return service.detail(LocalAccountApi.user(r),id,true);}
    public record Preview(String code,long shopId){}
    @PostMapping("/v2/ops/coupon-preview") public Object preview(HttpServletRequest r,@RequestBody Preview body){return service.preview(LocalAccountApi.user(r),body.code(),body.shopId());}
    @PostMapping("/v2/ops/coupon-redemptions") public Object redeem(HttpServletRequest r,@RequestBody Map<String,Object> body){return service.redeem(LocalAccountApi.user(r),body);}
    @PostMapping("/v2/ops/coupons/{id}/void") public Object voidCoupon(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> body){return service.voidCoupon(LocalAccountApi.user(r),id,body);}
    @GetMapping("/v2/ops/coupon-templates") public Object templates(HttpServletRequest r){return service.opsList(LocalAccountApi.user(r));}
    @PostMapping("/v2/ops/coupon-templates") public Object create(HttpServletRequest r,@RequestBody Map<String,Object> body){return service.saveTemplate(LocalAccountApi.user(r),null,body);}
    @PutMapping("/v2/ops/coupon-templates/{id}") public Object save(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> body){return service.saveTemplate(LocalAccountApi.user(r),id,body);}
    @PatchMapping("/v2/ops/coupon-templates/{id}/state") public Object state(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> body){return service.templateState(LocalAccountApi.user(r),id,body);}
}
