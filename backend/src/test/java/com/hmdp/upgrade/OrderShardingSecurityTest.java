package com.hmdp.upgrade;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderShardingSecurityTest {
    static final String SECRET="sharding-http-test-secret-at-least-32-characters";
    String token(String role) throws Exception {
        String payload="41:"+role+":"+(System.currentTimeMillis()/1000+300);
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return "Bearer "+payload+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void historyMaintenanceRequiresAdministratorAndActorComesFromIdentity() throws Exception {
        var history=mock(ExternalOrderHistory.class);when(history.status()).thenReturn(Map.of("enabled",true));
        var auth=new Auth(SECRET);ReflectionTestUtils.setField(auth,"laboratory",true);
        var mvc=MockMvcBuilders.standaloneSetup(new OrderShardingApi(history)).setControllerAdvice(new Errors()).addFilters(auth).build();
        mvc.perform(get("/v2/admin/order-sharding")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v2/admin/order-sharding").header("Authorization",token("user"))).andExpect(status().isForbidden());
        verifyNoInteractions(history);
        mvc.perform(get("/v2/admin/order-sharding").header("Authorization",token("admin"))).andExpect(status().isOk());
        mvc.perform(post("/v2/admin/order-sharding/read-mode").header("Authorization",token("admin")).contentType("application/json").content("{\"mode\":\"SHARDED\",\"actor\":999}")).andExpect(status().isOk());
        verify(history).readMode("SHARDED",41L);
    }
}
