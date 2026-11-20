package com.hmdp.upgrade;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class LocalAccountApi {
    private final LocalAccounts service;
    public LocalAccountApi(LocalAccounts service){this.service=service;}
    static long user(HttpServletRequest r){var identity=(Auth.Identity)r.getAttribute("identity");if(identity==null)throw new Problem(401,"UNAUTHORIZED");return identity.user();}
    static Long optionalUser(HttpServletRequest r){var identity=(Auth.Identity)r.getAttribute("identity");return identity==null?null:identity.user();}
    @GetMapping("/v2/account/profile") public Object profile(HttpServletRequest r){return service.profile(user(r));}
    @PatchMapping("/v2/account/profile") public Object profile(HttpServletRequest r,@RequestBody Map<String,Object> body){return service.profile(user(r),body);}
    public record Password(String oldPassword,String newPassword){}
    @PostMapping("/v2/account/password") public Object password(HttpServletRequest r,@RequestBody Password body){service.changePassword(user(r),body.oldPassword(),body.newPassword());return Map.of("changed",true);}
    public record RecoveryCode(String password){}
    @PostMapping("/v2/account/recovery-code") public Object code(HttpServletRequest r,@RequestBody RecoveryCode body){return service.recoveryCode(user(r),body.password());}
    public record Recovery(String username,String recoveryCode,String newPassword){}
    @PostMapping("/v2/auth/recover") public Object recover(HttpServletRequest r,@RequestBody Recovery body){return service.recover(body.username(),body.recoveryCode(),body.newPassword(),r.getRemoteAddr());}
}
