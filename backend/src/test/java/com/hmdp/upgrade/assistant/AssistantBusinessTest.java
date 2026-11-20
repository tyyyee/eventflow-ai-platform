package com.hmdp.upgrade.assistant;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.upgrade.Problem;
import org.junit.jupiter.api.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.hmdp.upgrade.assistant.AssistantData.*;

class AssistantBusinessTest {
    AssistantTestSupport f;
    @BeforeEach void setup(){f=new AssistantTestSupport();}
    @Test void discoveryRetainsConstraintsAndResolvesOrdinalAgainstDisplayedList(){
        String s=f.service.create(101).id();
        Run first=f.turn(s,"明天晚上预算100元，推荐音乐活动");assertEquals("SUCCEEDED",first.status(),first.errorCode());assertEquals(3,first.reply().activities().size());
        Run second=f.turn(s,"换成主校区的，其他条件不变");assertEquals(List.of("1","2"),second.reply().activities().stream().map(Activity::activityId).toList());
        State state=f.service.session(101,s).state();assertEquals(10000,state.criteria.maxPriceCents);assertEquals("音乐演出",state.criteria.category);assertEquals(18,state.criteria.hourFrom);assertEquals(f.tomorrow.toString(),state.criteria.dateFrom);
        Run detail=f.turn(s,"第二个还有票吗？");assertEquals("2",detail.reply().activities().get(0).activityId());
        Run shown=f.turn(s,"第一个还有票吗？");assertEquals("2",shown.reply().activities().get(0).activityId(),"ordinal must refer to the last displayed one-card detail");
        Run cheaper=f.turn(s,"太贵了，便宜一点");assertEquals(List.of("1"),cheaper.reply().activities().stream().map(Activity::activityId).toList());assertEquals(7999,f.service.session(101,s).state().criteria.maxPriceCents);
    }
    @Test void inventoryAndPricesUseTradingFactsNotPlanningShadowColumns(){
        String s=f.service.create(101).id();Run first=f.turn(s,"明天预算100元推荐音乐活动");
        Activity card=first.reply().activities().stream().filter(a->a.activityId().equals("1")).findFirst().orElseThrow();assertEquals(5000,card.priceCents());assertEquals(100,card.available());
        f.db.update("UPDATE ux_activity SET available=0,price_cents=5500 WHERE id=1");
        Run now=f.turn(s,"第二个还有票吗？");Activity refreshed=now.reply().activities().get(0);assertEquals("1",refreshed.activityId());assertEquals(0,refreshed.available());assertEquals(5500,refreshed.priceCents());assertEquals("SOLD_OUT",refreshed.saleStatus());
        assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM ux_request",Integer.class));
    }
    @Test void soldOutAlternativePreservesBudgetCampusAndCategory(){
        String s=f.service.create(101).id();f.turn(s,"明天晚上预算100元，推荐主校区音乐活动");f.db.update("UPDATE ux_activity SET available=0 WHERE id=2");
        Run result=f.turn(s,"第二个还有票吗，没票就换个类似的");assertEquals("SUCCEEDED",result.status(),result.errorCode());assertEquals(List.of("1"),result.reply().activities().stream().map(Activity::activityId).toList());
        assertTrue(f.service.steps(101,s,result.id()).stream().anyMatch(step->step.tool().equals("get_activity")));
        assertTrue(f.service.steps(101,s,result.id()).stream().anyMatch(step->step.tool().equals("search_activities")));
    }
    @Test void noMatchDoesNotRelaxRequirements(){
        String s=f.service.create(101).id();var result=f.turn(s,"明天预算1元，推荐主校区音乐活动");assertTrue(result.reply().activities().isEmpty());assertEquals(100,f.service.session(101,s).state().criteria.maxPriceCents);assertTrue(result.reply().text().contains("没有找到"));
    }
    @Test void resumeDoesNotRelabelOldEvidenceAsFreshAndPriceChangeWarnsAboutBudget(){
        var cp=new Checkpoint();cp.view="DETAIL";cp.activities=List.of(f.catalog.detail("1"));cp.state.criteria.maxPriceCents=100;
        cp.evidenceAt=cp.activities.get(0).checkedAt();var reply=AssistantReplies.render(cp,cp.evidenceAt.plusSeconds(45));
        assertEquals(cp.evidenceAt,reply.checkedAt());assertTrue(reply.text().contains("超过你设置的预算"));
    }
    @Test void explicitBudgetResetPreservesOtherFilters(){
        String s=f.service.create(101).id();f.turn(s,"明天预算1元，推荐主校区音乐活动");var result=f.turn(s,"预算不限");assertEquals(2,result.reply().activities().size());assertNull(f.service.session(101,s).state().criteria.maxPriceCents);
    }
    @Test void unlinkedOrUnpublishedSessionsNeverBecomePurchasableRecommendations(){
        f.db.update("UPDATE ux_experience_session SET activity_id=NULL WHERE id=1");f.db.update("UPDATE ux_storefront SET published=FALSE WHERE shop_id=2");
        String s=f.service.create(101).id();var result=f.turn(s,"明天预算100元，推荐音乐活动");assertEquals(List.of("3"),result.reply().activities().stream().map(Activity::activityId).toList());
    }
    @Test void timezoneAndEventEndTimeAreValidated(){
        Criteria criteria=new Criteria();criteria.dateFrom=f.tomorrow.toString();criteria.dateTo=criteria.dateFrom;criteria.hourFrom=19;criteria.hourTo=20;assertEquals(4,f.catalog.search(criteria,null).size());
        criteria.hourTo=19;assertEquals("ASSISTANT_INVALID_FILTER",assertThrows(Problem.class,()->f.catalog.search(criteria,null)).code);
        criteria.hourFrom=20;criteria.hourTo=22;assertTrue(f.catalog.search(criteria,null).isEmpty());
    }
    @Test void rulesComeFromPublishedContentAndDoNotInventRefundResults(){
        String s=f.service.create(101).id();Run result=f.turn(s,"购票有什么规则？");assertEquals(1,result.reply().rules().size());assertTrue(result.reply().rules().get(0).terms().contains("5分钟"));assertTrue(result.reply().text().contains("暂无法确认"));
        f.turn(s,"明天预算100元，推荐主校区音乐活动");result=f.turn(s,"第一个的入场规则是什么？");assertEquals(2,result.reply().rules().size());assertEquals("1",result.reply().rules().get(1).activityId());
    }
    @Test void ownOrderReadsDoNotCloseExpiredOrdersOrRevealOtherUsers(){
        f.request(101,"accepted","ACCEPTED",null,Instant.now());f.request(101,"mine","SUCCEEDED","PENDING_CONFIRM",Instant.now().minusSeconds(20));f.request(202,"secret","ACCEPTED",null,Instant.now());
        String s=f.service.create(101).id();Run result=f.turn(s,"帮我看看我的抢票结果");assertEquals(2,result.reply().orders().size());assertTrue(result.reply().orders().stream().noneMatch(o->o.requestId().equals("secret")));
        assertTrue(result.reply().orders().stream().filter(o->o.requestId().equals("accepted")).findFirst().orElseThrow().description().contains("尚不能确认"));
        assertTrue(result.reply().orders().stream().filter(o->o.requestId().equals("mine")).findFirst().orElseThrow().description().contains("时间已到"));
        assertEquals("PENDING_CONFIRM",f.db.queryForObject("SELECT state FROM ux_order WHERE request_id='mine'",String.class));
        assertEquals("REQUEST_NOT_FOUND",assertThrows(Problem.class,()->f.tools.execute("get_my_order",f.json.createObjectNode().put("requestId","secret"),101,new Checkpoint())).code);
    }
    @Test void modelSuppliedUserIdUnknownToolAndMalformedFiltersAreRejected(){
        var cp=new Checkpoint();
        assertEquals("ASSISTANT_INVALID_ARGUMENTS",assertThrows(Problem.class,()->f.tools.execute("get_my_orders",f.json.createObjectNode().put("userId",202),101,cp)).code);
        assertEquals("ASSISTANT_TOOL_FORBIDDEN",assertThrows(Problem.class,()->f.tools.execute("execute_sql",f.json.createObjectNode(),101,cp)).code);
        assertEquals("ASSISTANT_INVALID_ARGUMENTS",assertThrows(Problem.class,()->f.tools.execute("search_activities",f.json.createObjectNode().put("maxPriceCents","100"),101,cp)).code);
        assertEquals("ASSISTANT_UNKNOWN_CANDIDATE",assertThrows(Problem.class,()->f.tools.execute("get_activity",f.json.createObjectNode().put("activityId","1"),101,cp)).code);
    }
    @Test void purchaseAndPaymentRequestsProduceNoBusinessMutation(){
        String s=f.service.create(101).id();Run result=f.turn(s,"帮我自动抢票并付款");assertTrue(result.reply().text().contains("确认操作"));assertEquals(0,result.toolCalls());assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM ux_request",Integer.class));assertEquals(400,f.db.queryForObject("SELECT SUM(available) FROM ux_activity",Integer.class));
    }
    @Test void sameTurnKeyReplaysOneRunAndDifferentPayloadConflicts(){
        String s=f.service.create(101).id(),key=UUID.randomUUID().toString();var a=f.service.submit(101,s,key,"推荐音乐活动");var b=f.service.submit(101,s,key,"推荐音乐活动");assertEquals(a.id(),b.id());assertEquals("ASSISTANT_KEY_CONFLICT",assertThrows(Problem.class,()->f.service.submit(101,s,key,"看看订单")).code);f.service.workOne();assertEquals(1,f.service.session(101,s).turns().size());assertEquals(a.id(),f.service.submit(101,s,key,"推荐音乐活动").id());
    }
    @Test void concurrentTurnsAndCrossSessionConcurrentTurnsAreRejected()throws Exception{
        String s=f.service.create(101).id(),other=f.service.create(101).id();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{
            List<Future<String>> results=new ArrayList<>();for(String id:List.of(s,other))results.add(pool.submit(()->{start.await();try{return f.service.submit(101,id,UUID.randomUUID().toString(),"推荐音乐活动").status();}catch(Problem p){return p.code;}}));start.countDown();
            var outcomes=List.of(results.get(0).get(),results.get(1).get());assertTrue(outcomes.contains("QUEUED"));assertTrue(outcomes.contains("ASSISTANT_BUSY"));
        }finally{pool.shutdownNow();}
    }
    @Test void sessionRunAndTraceAreOwnerScoped(){
        String s=f.service.create(101).id();var result=f.turn(s,"推荐活动");
        assertEquals(404,assertThrows(Problem.class,()->f.service.session(202,s)).status);assertEquals(404,assertThrows(Problem.class,()->f.service.get(202,s,result.id())).status);assertEquals(404,assertThrows(Problem.class,()->f.service.steps(202,s,result.id())).status);assertEquals(404,assertThrows(Problem.class,()->f.service.delete(202,s)).status);
    }
    @Test void expiredQueuedRunDoesNotExecuteAndReleasesSession(){
        String s=f.service.create(101).id();Run run=f.service.submit(101,s,UUID.randomUUID().toString(),"推荐活动");f.db.update("UPDATE ux_assistant_run SET deadline=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),run.id());
        assertEquals("TIMED_OUT",f.service.get(101,s,run.id()).status());assertFalse(f.service.workOne());assertNull(f.service.session(101,s).activeRun());
    }
    @Test void unknownInFlightModelUsageFailsClosedAfterRestart(){
        String s=f.service.create(101).id();Run run=f.service.submit(101,s,UUID.randomUUID().toString(),"推荐活动");var cp=new Checkpoint();cp.modelInFlight=true;
        f.db.update("UPDATE ux_assistant_run SET status='RUNNING',owner='lost',lease_until=?,checkpoint_json=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),write(cp),run.id());
        f.service.workOne();Run result=f.service.get(101,s,run.id());assertEquals("FAILED",result.status());assertEquals("ASSISTANT_USAGE_UNKNOWN",result.errorCode());assertNull(result.totalTokens());assertNull(f.service.session(101,s).activeRun());
    }
    @Test void completedToolCheckpointResumesWithoutRepeatingThatStep()throws Exception{
        String s=f.service.create(101).id();Run run=f.service.submit(101,s,UUID.randomUUID().toString(),"推荐活动");var cp=new Checkpoint();
        cp.messages.add((ObjectNode)f.json.readTree("{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"saved\",\"type\":\"function\",\"function\":{\"name\":\"get_rules\",\"arguments\":\"{}\"}}]}"));
        cp.messages.add(f.json.createObjectNode().put("role","tool").put("tool_call_id","saved").put("content","[]"));cp.toolCalls=1;cp.decisions=1;cp.view="RULES";cp.rules=f.catalog.rules(null);
        f.db.update("UPDATE ux_assistant_run SET status='RUNNING',owner='lost',lease_until=?,checkpoint_json=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),write(cp),run.id());
        var restarted=new AssistantService(f.db,f.tx,f.json,new AssistantRuntime(new DemoAssistantModel(f.json),f.tools,f.json));restarted.workOne();Run done=restarted.get(101,s,run.id());assertEquals("SUCCEEDED",done.status());assertEquals(1,done.toolCalls());assertEquals(1,done.reply().rules().size());assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM ux_assistant_step WHERE run_id=?",Integer.class,run.id()));
    }
    @Test void expiredLeaseWorkerCannotOverwriteNewOwner(){
        AtomicInteger calls=new AtomicInteger();AssistantModel stealing=new AssistantModel(){public String mode(){return "stub";}public ModelAnswer next(Checkpoint cp,String message,Instant now,long remaining){calls.incrementAndGet();f.db.update("UPDATE ux_assistant_run SET owner='new-worker'");return new ModelAnswer(f.json.createObjectNode().put("role","assistant"),0);}};
        var svc=new AssistantService(f.db,f.tx,f.json,new AssistantRuntime(stealing,f.tools,f.json));String s=svc.create(101).id();var run=svc.submit(101,s,UUID.randomUUID().toString(),"推荐活动");svc.workOne();assertEquals(1,calls.get());assertEquals("RUNNING",svc.get(101,s,run.id()).status());assertEquals("new-worker",f.db.queryForObject("SELECT owner FROM ux_assistant_run WHERE id=?",String.class,run.id()));
    }
    @Test void toolLoopStopsAtBudgetAndNeverExecutesUnknownTools(){
        AssistantModel bad=new AssistantModel(){public String mode(){return "stub";}public ModelAnswer next(Checkpoint cp,String m,Instant now,long ms){
            ObjectNode answer=f.json.createObjectNode().put("role","assistant");ObjectNode call=f.json.createObjectNode().put("id","bad_"+cp.decisions).put("type","function");call.set("function",f.json.createObjectNode().put("name","delete_database").put("arguments","{}"));answer.putArray("tool_calls").add(call);return new ModelAnswer(answer,0);
        }};
        var svc=new AssistantService(f.db,f.tx,f.json,new AssistantRuntime(bad,f.tools,f.json));String s=svc.create(101).id();var run=f.turn(svc,s,"推荐活动");assertEquals("FAILED",run.status());assertEquals("ASSISTANT_BUDGET_EXCEEDED",run.errorCode());assertEquals(5,run.toolCalls());assertEquals(4,f.db.queryForObject("SELECT COUNT(*) FROM ux_activity",Integer.class));
    }
    @Test void aTurnCannotRelaxCriteriaAfterAnEmptySearchButNewUserTurnCan(){
        var cp=new Checkpoint();f.tools.execute("search_activities",f.json.createObjectNode().put("category","音乐演出").put("maxPriceCents",1),101,cp);
        assertTrue(cp.activities.isEmpty());
        var patch=f.json.createObjectNode();patch.putNull("category");
        assertEquals("ASSISTANT_CONSTRAINT_CHANGE",assertThrows(Problem.class,()->f.tools.execute("search_activities",patch,101,cp)).code);
        assertEquals("音乐演出",cp.state.criteria.category);
        var next=new Checkpoint();next.state=cp.state;f.tools.execute("search_activities",patch,101,next);assertNull(next.state.criteria.category);
    }
    @Test void detailReadsKeepOtherOrdersFromTheSameTurn(){
        f.request(101,"first","ACCEPTED",null,Instant.now());f.request(101,"second","SUCCEEDED","CONFIRMED",Instant.now());
        var cp=new Checkpoint();f.tools.execute("get_my_orders",f.json.createObjectNode(),101,cp);
        f.tools.execute("get_my_order",f.json.createObjectNode().put("requestId","first"),101,cp);
        f.tools.execute("get_my_order",f.json.createObjectNode().put("requestId","second"),101,cp);
        assertEquals(Set.of("first","second"),new HashSet<>(cp.orders.stream().map(item->item.requestId()).toList()));
    }
    @Test void supplementaryRulesKeepActivityCardsAndOldestEvidenceTime(){
        var cp=new Checkpoint();f.tools.execute("search_activities",f.json.createObjectNode(),101,cp);Instant at=cp.activities.get(0).checkedAt();
        f.tools.execute("get_rules",f.json.createObjectNode().put("position",1),101,cp);
        var reply=AssistantReplies.render(cp,Instant.now());assertFalse(reply.activities().isEmpty());assertFalse(reply.rules().isEmpty());assertEquals(at,reply.checkedAt());
    }
    private String write(Object value){try{return f.json.writeValueAsString(value);}catch(Exception e){throw new RuntimeException(e);}}
}
