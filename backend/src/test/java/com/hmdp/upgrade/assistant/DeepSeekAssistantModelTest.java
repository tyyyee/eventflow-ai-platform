package com.hmdp.upgrade.assistant;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.hmdp.upgrade.Problem;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.hmdp.upgrade.assistant.AssistantData.*;

class DeepSeekAssistantModelTest {
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();HttpServer server;String base;
    @BeforeEach void start()throws Exception{server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);base="http://127.0.0.1:"+server.getAddress().getPort();server.start();}
    @AfterEach void stop(){server.stop(0);}
    void respond(java.util.function.Function<JsonNode,String> response){
        server.createContext("/chat/completions",exchange->{
            assertEquals("Bearer local-contract-key",exchange.getRequestHeaders().getFirst("Authorization"));
            JsonNode request=json.readTree(exchange.getRequestBody());byte[] bytes=response.apply(request).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
    }
    DeepSeekAssistantModel model(){return new DeepSeekAssistantModel(json,"local-contract-key",base,"contract-model",true);}
    @Test void actualHttpToolCallContinuationAndFinalBusinessFacts()throws Exception{
        AtomicInteger calls=new AtomicInteger();AtomicReference<JsonNode> first=new AtomicReference<>();
        respond(request->{int call=calls.incrementAndGet();if(call==1){first.set(request);return "{\"usage\":{\"total_tokens\":42},\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"tool_1\",\"type\":\"function\",\"function\":{\"name\":\"search_activities\",\"arguments\":\"{\\\"maxPriceCents\\\":10000,\\\"category\\\":\\\"音乐演出\\\"}\"}}]}}]}";}
            assertEquals("tool",request.path("messages").path(request.path("messages").size()-1).path("role").asText());
            assertEquals("tool_1",request.path("messages").path(request.path("messages").size()-1).path("tool_call_id").asText());
            return "{\"usage\":{\"total_tokens\":51},\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"所有票免费且已经替你付款成功\"}}]}";
        });
        var f=new AssistantTestSupport(model());String s=f.service.create(101).id();Run result=f.turn(s,"推荐音乐活动");assertEquals("SUCCEEDED",result.status(),result.errorCode());assertEquals(2,result.modelCalls());assertEquals(93,result.totalTokens());assertEquals(3,result.reply().activities().size());assertFalse(result.reply().text().contains("付款成功"));assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM ux_order",Integer.class));
        assertEquals("disabled",first.get().path("thinking").path("type").asText());assertEquals(6,first.get().path("tools").size());
        JsonNode properties=first.get().path("tools").path(3).path("function").path("parameters").path("properties");assertFalse(properties.has("userId"));
    }
    @Test void missingUsageFailsWithoutExecutingToolOrSilentlySwitchingToDemo(){
        AtomicInteger calls=new AtomicInteger();respond(request->{calls.incrementAndGet();return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"成功\"}}]}";});
        var f=new AssistantTestSupport(model());String s=f.service.create(101).id();Run result=f.turn(s,"推荐音乐活动");assertEquals("FAILED",result.status());assertEquals("ASSISTANT_USAGE_UNKNOWN",result.errorCode());assertEquals(1,calls.get());assertEquals("deepseek",result.mode());assertNull(result.totalTokens());assertEquals(0,result.toolCalls());
    }
    @Test void providerRedirectIsNotFollowed(){
        AtomicInteger unexpected=new AtomicInteger();server.createContext("/unexpected",exchange->{unexpected.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});
        server.createContext("/chat/completions",exchange->{exchange.getResponseHeaders().set("Location",base+"/unexpected");exchange.sendResponseHeaders(302,-1);exchange.close();});
        var cp=new Checkpoint();cp.messages.add(json.createObjectNode().put("role","user").put("content","hello"));
        var failure=assertThrows(Problem.class,()->model().next(cp,"hello",Instant.now(),3000));assertEquals("ASSISTANT_MODEL_UNAVAILABLE",failure.code);assertEquals(0,unexpected.get());assertFalse(failure.getMessage().contains("local-contract-key"));
    }
    @Test void runtimeConfigurationRequiresHttpsAndExplicitKey(){
        assertThrows(IllegalArgumentException.class,()->new DeepSeekAssistantModel(json,"",base,"m",false));
        assertThrows(IllegalArgumentException.class,()->new DeepSeekAssistantModel(json,"key",base,"m",false));
        assertThrows(IllegalArgumentException.class,()->new DeepSeekAssistantModel(json,"key","https://user:password@example.invalid","m",false));
    }
}
