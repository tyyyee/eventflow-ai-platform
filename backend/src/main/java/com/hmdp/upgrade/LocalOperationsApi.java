package com.hmdp.upgrade;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class LocalOperationsApi {
    final LocalOperations service;public LocalOperationsApi(LocalOperations service){this.service=service;}
    @GetMapping("/v2/ops/overview") public Object overview(HttpServletRequest r){return service.overview(LocalAccountApi.user(r));}
    @GetMapping("/v2/local/policies") public Object policies(@RequestParam(defaultValue="") String q,@RequestParam(required=false) String id){var items=id==null?service.policies(q):service.policy(id);return Map.of("items",items);}
    @GetMapping("/v2/ops/policies") public Object all(HttpServletRequest r){return service.allPolicies(LocalAccountApi.user(r));}
    @PostMapping("/v2/ops/policies") public Object create(HttpServletRequest r,@RequestBody Map<String,Object> b){return service.savePolicy(LocalAccountApi.user(r),null,b);}
    @PutMapping("/v2/ops/policies/{id}") public Object save(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> b){return service.savePolicy(LocalAccountApi.user(r),id,b);}
    @PostMapping("/v2/account/feedback") public Object feedback(HttpServletRequest r,@RequestBody Map<String,Object> b){return service.feedback(LocalAccountApi.user(r),b);}
    @GetMapping("/v2/account/feedback") public Object mine(HttpServletRequest r){return service.myFeedback(LocalAccountApi.user(r));}
    @GetMapping("/v2/ops/{type:feedback|reports}") public Object issues(HttpServletRequest r,@PathVariable String type){return service.issues(LocalAccountApi.user(r),type);}
    @PatchMapping("/v2/ops/{type:feedback|reports}/{id}") public Object resolve(HttpServletRequest r,@PathVariable String type,@PathVariable String id,@RequestBody Map<String,Object> b){return service.resolve(LocalAccountApi.user(r),type,id,b);}
}
