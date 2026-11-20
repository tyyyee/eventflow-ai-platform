package com.hmdp.upgrade;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class LocalShopApi {
    private final LocalShops shops;private final LocalLifeConfiguration config;
    public LocalShopApi(LocalShops shops,LocalLifeConfiguration config){this.shops=shops;this.config=config;}
    @GetMapping("/v2/local/landmarks") public Object landmarks(){config.require();return Map.of("items",LocalShops.LANDMARKS);}
    @GetMapping("/v2/local/shops") public Object list(@RequestParam Map<String,String> filters,HttpServletRequest r){return shops.list(filters,LocalAccountApi.optionalUser(r));}
    @GetMapping("/v2/local/shops/{id}") public Object detail(@PathVariable long id,HttpServletRequest r){return shops.detail(id,LocalAccountApi.optionalUser(r));}
    @GetMapping("/v2/account/favorites") public Object favorites(HttpServletRequest r){return shops.favorites(LocalAccountApi.user(r));}
    @PutMapping("/v2/account/favorites/{type}/{id}") public Object favorite(HttpServletRequest r,@PathVariable String type,@PathVariable long id){return shops.favorite(LocalAccountApi.user(r),type,id,true);}
    @DeleteMapping("/v2/account/favorites/{type}/{id}") public Object unfavorite(HttpServletRequest r,@PathVariable String type,@PathVariable long id){return shops.favorite(LocalAccountApi.user(r),type,id,false);}
    @GetMapping("/v2/ops/shops") public Object ops(HttpServletRequest r){return shops.opsList(LocalAccountApi.user(r));}
    @PostMapping("/v2/ops/shops") public Object create(HttpServletRequest r,@RequestBody Map<String,Object> body){return shops.save(LocalAccountApi.user(r),null,body);}
    @PutMapping("/v2/ops/shops/{id}") public Object save(HttpServletRequest r,@PathVariable long id,@RequestBody Map<String,Object> body){return shops.save(LocalAccountApi.user(r),id,body);}
}
