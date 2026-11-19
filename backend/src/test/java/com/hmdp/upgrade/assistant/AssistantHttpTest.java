package com.hmdp.upgrade.assistant;

import com.hmdp.upgrade.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class AssistantHttpTest {
    AssistantTestSupport f;MockMvc mvc;
    @BeforeEach void setup(){
        f=new AssistantTestSupport();Auth auth=new Auth("a".repeat(40));Accounts accounts=mock(Accounts.class);
        when(accounts.authenticate("alice")).thenReturn(new Accounts.Session(new Accounts.User("101","alice","Alice"),"csrf",Instant.now().plusSeconds(3600)));
        when(accounts.authenticate("bob")).thenReturn(new Accounts.Session(new Accounts.User("202","bob","Bob"),"csrf",Instant.now().plusSeconds(3600)));
        ReflectionTestUtils.setField(auth,"accounts",accounts);
        mvc=MockMvcBuilders.standaloneSetup(new AssistantApi(f.service,f.catalog)).setControllerAdvice(new Errors())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(f.json)).addFilters(auth).build();
    }
    @Test void assistantRequiresLoginAndCookiePostsRequireCsrf()throws Exception{
        mvc.perform(get("/v2/assistant/sessions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v2/assistant/sessions").cookie(new Cookie("life_session","alice"))).andExpect(status().isForbidden());
        mvc.perform(post("/v2/assistant/sessions").cookie(new Cookie("life_session","alice")).header("X-CSRF-Token","csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.id").exists());
    }
    @Test void otherUserCannotReadSubmitDeleteOrReadTrace()throws Exception{
        String s=f.service.create(101).id(),r=f.turn(s,"购票规则是什么").id(),path="/v2/assistant/sessions/"+s;
        mvc.perform(get(path).cookie(new Cookie("life_session","bob"))).andExpect(status().isNotFound());
        mvc.perform(get(path+"/turns/"+r+"/steps").cookie(new Cookie("life_session","bob"))).andExpect(status().isNotFound());
        mvc.perform(post(path+"/turns").cookie(new Cookie("life_session","bob")).header("X-CSRF-Token","csrf").contentType("application/json").content("{\"requestKey\":\"attempt_001\",\"message\":\"我的订单\"}")).andExpect(status().isNotFound());
        mvc.perform(delete(path).cookie(new Cookie("life_session","bob")).header("X-CSRF-Token","csrf")).andExpect(status().isNotFound());
    }
    @Test void enqueuePollTraceAndRefreshRestoreConversation()throws Exception{
        String s=f.service.create(101).id(),path="/v2/assistant/sessions/"+s;
        String response=mvc.perform(post(path+"/turns").cookie(new Cookie("life_session","alice")).header("X-CSRF-Token","csrf").contentType("application/json").content("{\"requestKey\":\"attempt_001\",\"message\":\"明天预算100元，推荐音乐活动\"}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED")).andReturn().getResponse().getContentAsString();
        String run=f.json.readTree(response).path("id").asText();f.service.workOne();
        mvc.perform(get(path+"/turns/"+run).cookie(new Cookie("life_session","alice"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED")).andExpect(jsonPath("$.reply.activities.length()").value(3)).andExpect(jsonPath("$.modelCalls").value(0));
        mvc.perform(get(path+"/turns/"+run+"/steps").cookie(new Cookie("life_session","alice"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].tool").value("search_activities")).andExpect(jsonPath("$[0].arguments").doesNotExist());
        mvc.perform(get(path).cookie(new Cookie("life_session","alice"))).andExpect(status().isOk()).andExpect(jsonPath("$.turns.length()").value(1)).andExpect(jsonPath("$.state.criteria.maxPriceCents").value(10000));
    }
    @Test void switchedAccountHeaderAndCrossSiteRequestsAreRejected()throws Exception{
        mvc.perform(get("/v2/assistant/sessions").cookie(new Cookie("life_session","alice")).header("X-Account-ID","202")).andExpect(status().isConflict());
        mvc.perform(post("/v2/assistant/sessions").cookie(new Cookie("life_session","alice")).header("X-CSRF-Token","csrf").header("Host","localhost").header("Origin","https://example.invalid")).andExpect(status().isForbidden());
    }
}
