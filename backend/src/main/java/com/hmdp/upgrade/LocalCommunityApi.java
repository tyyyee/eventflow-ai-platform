package com.hmdp.upgrade;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class LocalCommunityApi {
    final LocalCommunity service;public LocalCommunityApi(LocalCommunity service){this.service=service;}
    @GetMapping("/v2/reviews") public Object feed(HttpServletRequest r,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="latest") String sort){return service.reviews(null,null,LocalAccountApi.optionalUser(r),page,sort);}
    @GetMapping("/v2/local/shops/{id}/reviews") public Object list(HttpServletRequest r,@PathVariable long id,@RequestParam(defaultValue="1") int page){return service.reviews(id,null,LocalAccountApi.optionalUser(r),page,"latest");}
    @GetMapping("/v2/reviews/{id}") public Object detail(HttpServletRequest r,@PathVariable String id){return service.detail(id,LocalAccountApi.optionalUser(r));}
    @GetMapping("/v2/account/reviews") public Object mine(HttpServletRequest r,@RequestParam(defaultValue="1") int page){long user=LocalAccountApi.user(r);return service.reviews(null,user,user,page,"latest");}
    @PostMapping("/v2/local/shops/{id}/reviews") public Object create(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){return service.review(LocalAccountApi.user(r),id,null,b);}
    @PatchMapping("/v2/reviews/{id}") public Object update(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> b){return service.review(LocalAccountApi.user(r),LocalData.number(b.get("shopId")),id,b);}
    @DeleteMapping("/v2/reviews/{id}") public Object delete(HttpServletRequest r,@PathVariable String id,@RequestParam long version){return service.deleteReview(LocalAccountApi.user(r),id,version);}
    @PutMapping("/v2/reviews/{id}/like") public Object like(HttpServletRequest r,@PathVariable String id){return service.like(LocalAccountApi.user(r),id,true);}
    @DeleteMapping("/v2/reviews/{id}/like") public Object unlike(HttpServletRequest r,@PathVariable String id){return service.like(LocalAccountApi.user(r),id,false);}
    @PostMapping("/v2/reviews/{id}/reports") public Object report(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,String> b){return service.report(LocalAccountApi.user(r),id,b.get("reason"));}
    @GetMapping("/v2/account/signs") public Object signs(HttpServletRequest r){return service.signs(LocalAccountApi.user(r));}
    @PutMapping("/v2/account/signs/today") public Object sign(HttpServletRequest r){return service.sign(LocalAccountApi.user(r));}
    @PostMapping("/v2/local/shops/{id}/checkins") public Object checkin(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> b){return service.checkin(LocalAccountApi.user(r),id,b);}
    @GetMapping("/v2/account/checkins") public Object checkins(HttpServletRequest r){return service.checkins(LocalAccountApi.user(r));}
    @DeleteMapping("/v2/account/checkins/{id}") public Object deleteCheckin(HttpServletRequest r,@PathVariable String id){service.deleteCheckin(LocalAccountApi.user(r),id);return Map.of("deleted",true);}
    @GetMapping("/v2/ops/reviews") public Object ops(HttpServletRequest r){return service.opsReviews(LocalAccountApi.user(r));}
    @PatchMapping("/v2/ops/reviews/{id}/state") public Object moderate(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> b){return service.moderate(LocalAccountApi.user(r),id,b);}
}
