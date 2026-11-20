package com.hmdp.upgrade;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

@Component
public class LocalLifeConfiguration {
    public final boolean enabled;
    private final boolean core32;
    public LocalLifeConfiguration(@Value("${upgrade.local-life.enabled:false}") boolean enabled,
                                  @Value("${upgrade.core32.enabled:false}") boolean core32) {
        this.enabled=enabled;this.core32=core32;
    }
    @PostConstruct void validate(){if(enabled&&core32)throw new IllegalStateException("LOCAL_LIFE_ENABLED and CORE32_ENABLED cannot both be true; local-life v1 requires single-database mode");}
    public void require(){if(!enabled)throw new Problem(503,"LOCAL_LIFE_DISABLED");}
    public Map<String,Object> capabilities(){return Map.of("localLife",enabled,"core32",core32,"payments","sandbox","brand",enabled?"趣生活":"趣票");}
}

@RestController
class LocalLifeCapabilitiesApi {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private LocalSeed seed;
    private final LocalLifeConfiguration config;
    LocalLifeCapabilitiesApi(LocalLifeConfiguration config){this.config=config;}
    @GetMapping("/v2/capabilities") public Object capabilities(){var values=new java.util.LinkedHashMap<>(config.capabilities());values.put("demoReady",seed==null||seed.ready());return values;}
}
