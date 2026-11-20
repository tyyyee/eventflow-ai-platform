package com.hmdp.upgrade.assistant;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated MySQL/Flyway, Redis, RocketMQ and HTTP. No external model is invoked. */
@EnabledIfEnvironmentVariable(named="ASSISTANT_INTEGRATION",matches="true")
@org.springframework.test.annotation.DirtiesContext
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"upgrade.demo-data=true","upgrade.assistant.mode=stub","upgrade.jobs=true","upgrade.sandbox-payments=true"})
class AssistantIntegrationTest {
    @Autowired TestRestTemplate http;@Autowired JdbcTemplate db;
    record Client(String cookie,String csrf){}
    ResponseEntity<Map> call(HttpMethod method,String path,Client client,Object body){
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(client!=null){headers.set("Cookie",client.cookie());headers.set("X-CSRF-Token",client.csrf());}
        return http.exchange("/v2"+path,method,new HttpEntity<>(body,headers),Map.class);
    }
    Client user(){String name="agent_"+UUID.randomUUID().toString().replace("-","").substring(0,14);var result=call(HttpMethod.POST,"/auth/register",null,Map.of("username",name,"password","local-assistant-integration-pass","displayName","助手验收"));assertEquals(201,result.getStatusCode().value());return new Client(result.getHeaders().getFirst("Set-Cookie").split(";")[0],(String)result.getBody().get("csrfToken"));}
    String session(Client client){var result=call(HttpMethod.POST,"/assistant/sessions",client,null);assertEquals(200,result.getStatusCode().value());return (String)result.getBody().get("id");}
    Map turn(Client client,String s,String message)throws Exception{
        var accepted=call(HttpMethod.POST,"/assistant/sessions/"+s+"/turns",client,Map.of("requestKey",UUID.randomUUID().toString(),"message",message));assertEquals(202,accepted.getStatusCode().value(),accepted.toString());String id=(String)accepted.getBody().get("id");
        for(int i=0;i<100;i++){var result=call(HttpMethod.GET,"/assistant/sessions/"+s+"/turns/"+id,client,null);assertEquals(200,result.getStatusCode().value());String state=(String)result.getBody().get("status");if(!Set.of("QUEUED","RUNNING").contains(state)){assertEquals("SUCCEEDED",state,result.toString());return result.getBody();}Thread.sleep(100);}
        throw new AssertionError("assistant run did not finish");
    }
    @Test void flywayNativeInventoryQueriesMultiTurnAndOwnerIsolation()throws Exception{
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='17' AND success=TRUE",Integer.class));
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM ux_experience_session WHERE activity_id IS NOT NULL",Integer.class)>0);
        Client alice=user(),bob=user();String s=session(alice);var found=turn(alice,s,"明天预算100元，推荐音乐活动");var cards=(List<Map>)((Map)found.get("reply")).get("activities");assertFalse(cards.isEmpty());
        Map first=cards.get(0);long activity=Long.parseLong((String)first.get("activityId"));assertEquals(db.queryForObject("SELECT price_cents FROM ux_activity WHERE id=?",Integer.class,activity),first.get("priceCents"));
        assertEquals(404,call(HttpMethod.GET,"/assistant/sessions/"+s,bob,null).getStatusCode().value());
        var main=turn(alice,s,"换成主校区的，其他条件不变");var mainCards=(List<Map>)((Map)main.get("reply")).get("activities");assertFalse(mainCards.isEmpty());assertTrue(mainCards.stream().allMatch(c->c.get("campus").toString().contains("主校区")));
        var restored=call(HttpMethod.GET,"/assistant/sessions/"+s,alice,null);var criteria=(Map)((Map)restored.getBody().get("state")).get("criteria");assertEquals(10000,criteria.get("maxPriceCents"));assertEquals("音乐演出",criteria.get("category"));
        var detail=turn(alice,s,"第一个还有票吗？");assertEquals(1,((List)((Map)detail.get("reply")).get("activities")).size());
    }
    @Test void existingRocketMqPurchaseCanBeReadByAssistantWithoutAnotherOrder()throws Exception{
        Client alice=user();String s=session(alice);var found=turn(alice,s,"明天预算100元推荐音乐活动");Map card=((List<Map>)((Map)found.get("reply")).get("activities")).get(0);long activity=Long.parseLong((String)card.get("activityId"));
        var accepted=call(HttpMethod.POST,"/requests",alice,Map.of("requestId",UUID.randomUUID().toString(),"activityId",activity));assertEquals(202,accepted.getStatusCode().value(),accepted.toString());String request=(String)accepted.getBody().get("id");
        boolean created=false;for(int i=0;i<150;i++){var result=call(HttpMethod.GET,"/account/requests/"+request,alice,null);Map item=(Map)result.getBody().get("item");if("SUCCEEDED".equals(item.get("state"))){created=true;break;}Thread.sleep(100);}assertTrue(created,"RocketMQ consumer must create the actual order");
        var orders=turn(alice,s,"看看我的抢票结果");var items=(List<Map>)((Map)orders.get("reply")).get("orders");assertEquals(1,items.size());assertEquals(request,items.get(0).get("requestId"));assertEquals("PENDING_CONFIRM",items.get(0).get("orderState"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ux_order WHERE request_id=?",Integer.class,request));
        var rules=turn(alice,s,"购票有什么规则？");assertFalse(((List)((Map)rules.get("reply")).get("rules")).isEmpty());
    }
}
