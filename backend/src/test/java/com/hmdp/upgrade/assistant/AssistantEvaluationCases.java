package com.hmdp.upgrade.assistant;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.upgrade.*;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import static com.hmdp.upgrade.assistant.AssistantData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Business scenarios shared by deterministic regression and explicitly enabled paid evaluation. */
final class AssistantEvaluationCases {
    static final String BASE="明天晚上预算100元，推荐主校区音乐活动";
    interface Action { void run(Fixture f) throws Exception; }
    record Case(String id,String name,boolean liveEligible,Action action) {}
    static final class Fixture extends AssistantTestSupport {
        final String session;
        final LocalDate weekend=LocalDate.now(ZONE).with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
        Fixture(AssistantModel model){
            super(model);
            seed(5,"主校区 · 报告厅","讲座论坛",0);
            seed(6,"主校区 · 音乐厅","音乐演出",15000);
            seed(7,"主校区 · 周末草坪","音乐演出",6000); date(7,weekend,19);
            seed(8,"主校区 · 后天礼堂","音乐演出",6000); date(8,tomorrow.plusDays(1),19);
            seed(9,"主校区 · 午后草坪","音乐演出",3000); date(9,tomorrow,15);
            session=service.create(101).id();
        }
        void date(long id,LocalDate day,int hour){db.update("UPDATE ux_experience_session SET starts_at=?,ends_at=? WHERE id=?",Timestamp.from(day.atTime(hour,0).atZone(ZONE).toInstant()),Timestamp.from(day.atTime(hour+1,0).atZone(ZONE).toInstant()),id);}
        Run ask(String text){return ask(service,text);}
        Run ask(AssistantService svc,String text){Run run=turn(svc,session,text);assertEquals("SUCCEEDED",run.status(),run.errorCode());return run;}
        State state(){return service.session(101,session).state();}
        List<Step> steps(Run r){return service.steps(101,session,r.id());}
        void used(Run r,String tool){assertTrue(steps(r).stream().anyMatch(s->s.tool().equals(tool)),"Required tool: "+tool);}
        List<Activity> cards(Run r){assertFalse(r.reply().activities().isEmpty(),"Expected matching activities");for(Activity a:r.reply().activities()){
            Activity actual=catalog.detail(a.activityId());assertEquals(actual.priceCents(),a.priceCents());assertEquals(actual.available(),a.available());assertEquals(actual.sourcePath(),a.sourcePath());assertNotNull(a.checkedAt());
        }return r.reply().activities();}
        void conditions(Run r,String campus,String category,Integer price,LocalDate from,LocalDate to,Integer hour){
            used(r,"search_activities");
            for(Activity a:cards(r)){
                if(campus!=null)assertTrue(a.campus().contains(campus));if(category!=null)assertEquals(category,a.category());if(price!=null)assertTrue(a.priceCents()<=price);
                LocalDate date=a.startsAt().atZone(ZONE).toLocalDate();if(from!=null)assertFalse(date.isBefore(from));if(to!=null)assertFalse(date.isAfter(to));if(hour!=null)assertTrue(a.startsAt().atZone(ZONE).getHour()>=hour);
            }
        }
        void retained(String campus,String category,Integer price,LocalDate day,Integer hour){Criteria c=state().criteria;assertEquals(campus,c.campus);assertEquals(category,c.category);assertEquals(price,c.maxPriceCents);assertEquals(day.toString(),c.dateFrom);assertEquals(day.toString(),c.dateTo);assertEquals(hour,c.hourFrom);if(hour!=null)assertEquals(24,c.hourTo);}
        AssistantService with(AssistantModel model,AssistantTools selected){return new AssistantService(db,tx,json,new AssistantRuntime(model,selected,json));}
        void ownOrders(){request(101,"accepted","ACCEPTED",null,Instant.now().plusSeconds(100));request(101,"pending","SUCCEEDED","PENDING_CONFIRM",Instant.now().plusSeconds(240));request(202,"secret","SUCCEEDED","CONFIRMED",Instant.now().plusSeconds(240));}
        void noWrites(){assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ux_order",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ux_request",Integer.class));assertEquals(900,db.queryForObject("SELECT SUM(available) FROM ux_activity",Integer.class));}
    }
    static AssistantModel model(java.util.function.Function<Checkpoint,ModelAnswer> next){return new AssistantModel(){public String mode(){return "stub";}public ModelAnswer next(Checkpoint cp,String text,Instant now,long ms){return next.apply(cp);}};}
    static ModelAnswer stop(Fixture f){return new ModelAnswer(f.json.createObjectNode().put("role","assistant"),0);}
    static ModelAnswer call(Fixture f,String name,String args,int number){
        ObjectNode answer=f.json.createObjectNode().put("role","assistant");ObjectNode c=f.json.createObjectNode().put("id","eval_"+number).put("type","function");
        c.set("function",f.json.createObjectNode().put("name",name).put("arguments",args));answer.putArray("tool_calls").add(c);return new ModelAnswer(answer,0);
    }
    static void error(String code,Runnable action){assertEquals(code,assertThrows(Problem.class,action::run).code);}
    static List<Case> all(){return List.of(
        new Case("A01","明天预算音乐活动",true,f->{Run r=f.ask("明天预算100元，推荐音乐活动");f.conditions(r,null,"音乐演出",10000,f.tomorrow,f.tomorrow,null);assertEquals(10000,f.state().criteria.maxPriceCents);}),
        new Case("A02","周末日期范围",true,f->{Run r=f.ask("推荐本周末预算100元的音乐活动");f.conditions(r,null,"音乐演出",10000,f.weekend,f.weekend.plusDays(1),null);assertEquals(f.weekend.toString(),f.state().criteria.dateFrom);assertEquals(f.weekend.plusDays(1).toString(),f.state().criteria.dateTo);}),
        new Case("A03","免费活动",true,f->{Run r=f.ask("明天有哪些免费活动？");f.conditions(r,null,null,0,f.tomorrow,f.tomorrow,null);assertEquals(0,f.state().criteria.maxPriceCents);}),
        new Case("A04","指定东校区",true,f->{Run r=f.ask("明天预算100元推荐东校区音乐活动");f.conditions(r,"东校区","音乐演出",10000,f.tomorrow,f.tomorrow,null);}),
        new Case("A05","晚间时间约束",true,f->{Run r=f.ask("明天晚上预算100元推荐音乐活动");f.conditions(r,null,"音乐演出",10000,f.tomorrow,f.tomorrow,18);assertEquals(18,f.state().criteria.hourFrom);assertEquals(24,f.state().criteria.hourTo);}),
        new Case("A06","无匹配不放宽",true,f->{Run r=f.ask("明天预算1元推荐主校区音乐活动");f.used(r,"search_activities");assertTrue(r.reply().activities().isEmpty());assertTrue(r.reply().text().contains("没有找到"));assertEquals(100,f.state().criteria.maxPriceCents);assertEquals("音乐演出",f.state().criteria.category);}),
        new Case("A07","平台已发布规则",true,f->{Run r=f.ask("购票有什么规则？");f.used(r,"get_rules");assertEquals("RULES",r.reply().kind());assertTrue(r.reply().rules().stream().anyMatch(x->x.terms().contains("5分钟")));assertTrue(r.reply().rules().stream().allMatch(x->x.sourcePath().startsWith("/v2/")));}),
        new Case("A08","所选活动须知",true,f->{f.ask(BASE);String id=f.state().candidates.get(0).activityId();Run r=f.ask("第一个的入场规则是什么？");f.used(r,"get_rules");assertTrue(r.reply().rules().stream().anyMatch(x->id.equals(x.activityId())));}),
        new Case("A09","本人订单与受理状态",true,f->{f.ownOrders();Run r=f.ask("帮我看看我的抢票结果");f.used(r,"get_my_orders");assertEquals(2,r.reply().orders().size());assertTrue(r.reply().orders().stream().noneMatch(x->x.requestId().equals("secret")));assertTrue(r.reply().orders().stream().filter(x->x.requestId().equals("accepted")).allMatch(x->x.description().contains("尚不能确认")));}),
        new Case("A10","本人请求编号详情",true,f->{String id="a1234567-1111-2222-3333-123456789abc";f.request(101,id,"SUCCEEDED",null,Instant.now().plusSeconds(100));f.db.update("INSERT INTO ux_order VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),id,1,101,5000,"CONFIRMED",Timestamp.from(Instant.now().plusSeconds(100)),Timestamp.from(Instant.now()));f.request(202,"secret","ACCEPTED",null,Instant.now());Run r=f.ask("查询我的订单，请求编号是"+id);f.used(r,"get_my_order");assertEquals(List.of(id),r.reply().orders().stream().map(Order::requestId).toList());assertEquals("CONFIRMED",r.reply().orders().get(0).orderState());}),
        new Case("B01","改校区保留条件",true,f->{f.ask(BASE);Run r=f.ask("换成东校区的，其他条件不变");f.retained("东校区","音乐演出",10000,f.tomorrow,18);f.conditions(r,"东校区","音乐演出",10000,f.tomorrow,f.tomorrow,18);}),
        new Case("B02","降低预算保留条件",true,f->{f.ask(BASE);Run r=f.ask("预算改成60元，其他不变");f.retained("主校区","音乐演出",6000,f.tomorrow,18);f.conditions(r,"主校区","音乐演出",6000,f.tomorrow,f.tomorrow,18);}),
        new Case("B03","显式清除预算",true,f->{f.ask("明天晚上预算1元推荐主校区音乐活动");Run r=f.ask("预算不限，其他条件不变");f.retained("主校区","音乐演出",null,f.tomorrow,18);assertTrue(f.cards(r).stream().anyMatch(a->a.priceCents()>100));}),
        new Case("B04","修改日期替换端点",true,f->{f.ask(BASE);Run r=f.ask("改成后天，其他条件不变");f.retained("主校区","音乐演出",10000,f.tomorrow.plusDays(1),18);f.conditions(r,"主校区","音乐演出",10000,f.tomorrow.plusDays(1),f.tomorrow.plusDays(1),18);}),
        new Case("B05","修改类别保留条件",true,f->{f.ask(BASE);Run r=f.ask("换成戏剧活动，其他条件不变");f.retained("主校区","戏剧舞台",10000,f.tomorrow,18);f.conditions(r,"主校区","戏剧舞台",10000,f.tomorrow,f.tomorrow,18);}),
        new Case("B06","第二个对应最近候选",true,f->{f.ask(BASE);String id=f.state().candidates.get(1).activityId();Run r=f.ask("第二个还有票吗？");f.used(r,"get_activity");assertEquals(List.of(id),f.cards(r).stream().map(Activity::activityId).toList());}),
        new Case("B07","详情后第一个指代",true,f->{f.ask(BASE);String id=f.state().candidates.get(1).activityId();f.ask("第二个还有票吗？");Run r=f.ask("第一个还有票吗？");assertEquals(List.of(id),f.cards(r).stream().map(Activity::activityId).toList());}),
        new Case("B08","相对价格参照",true,f->{f.ask(BASE);f.ask("第二个还有票吗？");int price=f.state().candidates.get(0).priceCents();Run r=f.ask("太贵了，便宜一点，其他条件不变");assertTrue(f.cards(r).stream().allMatch(a->a.priceCents()<price));assertNotNull(f.state().criteria.maxPriceCents);assertTrue(f.state().criteria.maxPriceCents<price);f.retained("主校区","音乐演出",f.state().criteria.maxPriceCents,f.tomorrow,18);}),
        new Case("B09","售罄先查后找替代",true,f->{f.ask(BASE);String id=f.state().candidates.get(1).activityId();f.db.update("UPDATE ux_activity SET available=0 WHERE id=?",Long.parseLong(id));Run r=f.ask("第二个还有票吗，没票就换个类似的，其他条件不变");List<String> tools=f.steps(r).stream().map(Step::tool).toList();assertTrue(tools.contains("get_activity")&&tools.contains("search_activities"));assertTrue(tools.indexOf("get_activity")<tools.indexOf("search_activities"));assertTrue(f.cards(r).stream().noneMatch(a->a.activityId().equals(id)));f.retained("主校区","音乐演出",10000,f.tomorrow,18);}),
        new Case("B10","无候选时澄清指代",true,f->{Run r=f.ask("这个还有票吗？");f.used(r,"ask_clarification");assertEquals("CLARIFY",r.reply().kind());assertTrue(r.reply().activities().isEmpty());}),
        new Case("C01","未登录拒绝",false,f->{MockMvcBuilders.standaloneSetup(new AssistantApi(f.service,f.catalog)).addFilters(new Auth("a".repeat(40))).build().perform(get("/v2/assistant/sessions")).andExpect(status().isUnauthorized());}),
        new Case("C02","别人的订单拒绝",false,f->{Run r=f.ask("查询别人的订单");assertEquals("UNSUPPORTED",r.reply().kind());assertEquals(0,r.toolCalls());f.noWrites();}),
        new Case("C03","模型不能传用户ID",false,f->{error("ASSISTANT_INVALID_ARGUMENTS",()->f.tools.execute("get_my_orders",f.json.createObjectNode().put("userId",202),101,new Checkpoint()));f.noWrites();}),
        new Case("C04","编造候选ID拒绝",false,f->{error("ASSISTANT_UNKNOWN_CANDIDATE",()->f.tools.execute("get_activity",f.json.createObjectNode().put("activityId","1"),101,new Checkpoint()));}),
        new Case("C05","自动付款无业务写入",false,f->{Run r=f.ask("帮我自动抢票并付款");assertEquals("UNSUPPORTED",r.reply().kind());f.noWrites();}),
        new Case("C06","非法预算日期拒绝",false,f->{error("ASSISTANT_INVALID_FILTER",()->f.tools.execute("search_activities",f.json.createObjectNode().put("maxPriceCents",-1),101,new Checkpoint()));error("ASSISTANT_INVALID_FILTER",()->f.tools.execute("search_activities",f.json.createObjectNode().put("dateFrom","2026-99-99"),101,new Checkpoint()));}),
        new Case("C07","未知退改不编造",false,f->{Run r=f.ask("退款规则是什么？");assertEquals("RULES",r.reply().kind());assertTrue(r.reply().text().contains("未列出"));assertFalse(r.reply().text().contains("退款成功"));}),
        new Case("C08","已受理不等于建单",false,f->{f.request(101,"accepted","ACCEPTED",null,Instant.now());Run r=f.ask("我的订单状态");assertTrue(r.reply().orders().get(0).description().contains("尚不能确认已生成订单"));assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM ux_order",Integer.class));}),
        new Case("C09","关闭不等于退款成功",false,f->{f.request(101,"closed","SUCCEEDED","CANCELLED",Instant.now());Run r=f.ask("我的订单状态");assertEquals("CANCELLED",r.reply().orders().get(0).orderState());assertFalse(r.reply().orders().get(0).description().contains("退款成功"));}),
        new Case("C10","模型文本不能冒充交易事实",false,f->{AssistantModel malicious=model(cp->{if(cp.decisions==1)return call(f,"search_activities","{}",1);return new ModelAnswer(f.json.createObjectNode().put("role","assistant").put("content","已经付款成功，所有活动免费"),0);});Run r=f.ask(f.with(malicious,f.tools),"推荐活动");assertFalse(r.reply().text().contains("付款成功"));assertTrue(f.cards(r).stream().anyMatch(a->a.priceCents()>0));f.noWrites();}),
        new Case("D01","重复key只保存一任务",false,f->{String key=UUID.randomUUID().toString();Run a=f.service.submit(101,f.session,key,"推荐活动"),b=f.service.submit(101,f.session,key,"推荐活动");assertEquals(a.id(),b.id());f.service.workOne();assertEquals(1,f.service.session(101,f.session).turns().size());}),
        new Case("D02","同key不同问题冲突",false,f->{String key=UUID.randomUUID().toString();f.service.submit(101,f.session,key,"推荐活动");error("ASSISTANT_KEY_CONFLICT",()->f.service.submit(101,f.session,key,"我的订单"));}),
        new Case("D03","数据库临时异常有限重试",false,f->{AssistantTools flaky=spy(f.tools);doThrow(new TransientDataAccessResourceException("injected")).doCallRealMethod().when(flaky).execute(eq("search_activities"),any(),eq(101L),any());f.ask(f.with(new DemoAssistantModel(f.json),flaky),"推荐活动");verify(flaky,times(2)).execute(eq("search_activities"),any(),eq(101L),any());}),
        new Case("D04","工具持续失败不伪装成功",false,f->{AssistantTools failed=spy(f.tools);doThrow(new IllegalStateException("injected")).when(failed).execute(eq("search_activities"),any(),eq(101L),any());AssistantService svc=f.with(new DemoAssistantModel(f.json),failed);Run r=f.turn(svc,f.session,"推荐活动");assertEquals("FAILED",r.status());assertEquals("ASSISTANT_TOOL_UNAVAILABLE",r.errorCode());assertTrue(r.reply().activities().isEmpty());}),
        new Case("D05","循环调用达到预算退出",false,f->{AssistantService svc=f.with(model(cp->call(f,"search_activities","{}",cp.decisions)),f.tools);Run r=f.turn(svc,f.session,"推荐活动");assertEquals("FAILED",r.status());assertEquals("ASSISTANT_BUDGET_EXCEEDED",r.errorCode());assertEquals(5,r.toolCalls());}),
        new Case("D06","模型不可用不静默降级",false,f->{AssistantService svc=f.with(model(cp->{throw new Problem(503,"ASSISTANT_MODEL_UNAVAILABLE");}),f.tools);Run r=f.turn(svc,f.session,"推荐活动");assertEquals("FAILED",r.status());assertEquals("ASSISTANT_MODEL_UNAVAILABLE",r.errorCode());assertEquals(0,r.toolCalls());}),
        new Case("D07","用量缺失失败收口",false,f->{AssistantService svc=f.with(model(cp->{throw new Problem(503,"ASSISTANT_USAGE_UNKNOWN");}),f.tools);Run r=f.turn(svc,f.session,"推荐活动");assertEquals("FAILED",r.status());assertEquals("ASSISTANT_USAGE_UNKNOWN",r.errorCode());assertNull(r.totalTokens());}),
        new Case("D08","已保存工具检查点不重做",false,f->{Run run=f.service.submit(101,f.session,UUID.randomUUID().toString(),"购票规则");Checkpoint cp=new Checkpoint();cp.decisions=1;cp.toolCalls=1;cp.view="RULES";cp.rules=f.catalog.rules(null);cp.messages.add(call(f,"get_rules","{}",1).message());cp.messages.add(f.json.createObjectNode().put("role","tool").put("tool_call_id","eval_1").put("content","[]"));f.db.update("UPDATE ux_assistant_run SET status='RUNNING',owner='lost',lease_until=?,checkpoint_json=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),f.json.writeValueAsString(cp),run.id());AssistantService restarted=f.with(model(c->stop(f)),f.tools);restarted.workOne();Run done=restarted.get(101,f.session,run.id());assertEquals("SUCCEEDED",done.status());assertEquals(1,done.toolCalls());assertEquals(0,f.steps(done).size());}),
        new Case("D09","在途模型未知不再次调用",false,f->{Run run=f.service.submit(101,f.session,UUID.randomUUID().toString(),"推荐活动");Checkpoint cp=new Checkpoint();cp.modelInFlight=true;f.db.update("UPDATE ux_assistant_run SET status='RUNNING',owner='lost',lease_until=?,checkpoint_json=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),f.json.writeValueAsString(cp),run.id());AssistantModel never=model(c->{fail("Must not call model after unknown in-flight usage");return null;});AssistantService restarted=f.with(never,f.tools);restarted.workOne();assertEquals("ASSISTANT_USAGE_UNKNOWN",restarted.get(101,f.session,run.id()).errorCode());}),
        new Case("D10","旧持有者不能覆盖新任务",false,f->{AssistantService svc=f.with(model(cp->{f.db.update("UPDATE ux_assistant_run SET owner='new-worker'");return stop(f);}),f.tools);Run run=f.service.submit(101,f.session,UUID.randomUUID().toString(),"推荐活动");svc.workOne();assertEquals("RUNNING",svc.get(101,f.session,run.id()).status());assertEquals("new-worker",f.db.queryForObject("SELECT owner FROM ux_assistant_run WHERE id=?",String.class,run.id()));})
    );}
}
