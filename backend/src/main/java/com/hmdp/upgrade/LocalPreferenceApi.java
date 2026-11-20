package com.hmdp.upgrade;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
public class LocalPreferenceApi {
    final LocalPreferences preferences;
    public LocalPreferenceApi(LocalPreferences preferences){this.preferences=preferences;}
    @GetMapping("/v2/account/preferences") public Object read(HttpServletRequest r){return preferences.read(LocalAccountApi.user(r));}
    @PutMapping("/v2/account/preferences") public Object save(HttpServletRequest r,@RequestBody Map<String,Object> body){return preferences.save(LocalAccountApi.user(r),body);}
    @DeleteMapping("/v2/account/preferences") public Object clear(HttpServletRequest r){return preferences.clear(LocalAccountApi.user(r));}
}
