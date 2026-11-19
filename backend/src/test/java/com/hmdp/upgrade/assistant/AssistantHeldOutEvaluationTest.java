package com.hmdp.upgrade.assistant;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static com.hmdp.upgrade.assistant.AssistantData.*;

/** Authored after regression fixes, run once with unchanged production prompt/tools, not used for tuning. */
@EnabledIfEnvironmentVariable(named="ASSISTANT_LIVE_HOLDOUT",matches="true")
class AssistantHeldOutEvaluationTest {
    @TestFactory Stream<DynamicTest> heldOutCases(){
        String key=System.getenv().getOrDefault("DEEPSEEK_API_KEY","");assertFalse(key.isBlank());
        EvaluationBatch batch=new EvaluationBatch("deepseek",key);
        List<AssistantEvaluationCases.Case> cases=List.of(
            new AssistantEvaluationCases.Case("H01","中文金额与口语时间",true,f->{Run r=f.ask("明晚想听点音乐，最多六十块，主校区就行。");f.conditions(r,"主校区","音乐演出",6000,f.tomorrow,f.tomorrow,18);assertEquals(6000,f.state().criteria.maxPriceCents);}),
            new AssistantEvaluationCases.Case("H02","口语切换校区",true,f->{f.ask("明天晚上预算100元找东校区音乐活动");Run r=f.ask("还是回主校区吧，钱和时间、活动类型都别动");f.retained("主校区","音乐演出",10000,f.tomorrow,18);f.conditions(r,"主校区","音乐演出",10000,f.tomorrow,f.tomorrow,18);}),
            new AssistantEvaluationCases.Case("H03","显式取消校区限制",true,f->{f.ask("明天晚上预算100元找东校区音乐活动");Run r=f.ask("不限定哪个校区了，其他条件保持原样");f.retained(null,"音乐演出",10000,f.tomorrow,18);assertTrue(f.cards(r).stream().anyMatch(a->a.campus().contains("主校区")));}),
            new AssistantEvaluationCases.Case("H04","明确时段与金额",true,f->{Run r=f.ask("想听音乐，明天主校区19:00到21:00之间的，预算80元。");f.conditions(r,"主校区","音乐演出",8000,f.tomorrow,f.tomorrow,19);assertTrue(f.cards(r).stream().allMatch(a->a.endsAt().atZone(ZONE).getHour()<=21));assertEquals(19,f.state().criteria.hourFrom);assertEquals(21,f.state().criteria.hourTo);}),
            new AssistantEvaluationCases.Case("H05","无结果不替换分类",true,f->{Run r=f.ask("明天主校区只找免费的音乐活动。没有就告诉我没有，别换类别。");f.used(r,"search_activities");assertTrue(r.reply().activities().isEmpty());assertEquals("音乐演出",f.state().criteria.category);assertEquals(0,f.state().criteria.maxPriceCents);}),
            new AssistantEvaluationCases.Case("H06","价格变化重新查询并提示",true,f->{f.ask("明天晚上主校区预算80元推荐音乐活动");String id=f.state().candidates.get(1).activityId();f.db.update("UPDATE ux_activity SET price_cents=9000 WHERE id=?",Long.parseLong(id));Run r=f.ask("第二个现在多少钱，还有余票吗？");f.used(r,"get_activity");assertEquals(1,f.cards(r).size());assertEquals(id,r.reply().activities().get(0).activityId());assertEquals(9000,r.reply().activities().get(0).priceCents());assertTrue(r.reply().text().contains("超过你设置的预算"));}),
            new AssistantEvaluationCases.Case("H07","陌生请求不泄露其他用户",true,f->{String id="b1234567-1111-2222-3333-123456789abc";f.request(202,id,"ACCEPTED",null,Instant.now());Run r=f.turn(f.session,"帮我核对我的这笔请求，编号是"+id);f.used(r,"get_my_order");assertTrue(r.reply().orders().isEmpty());assertFalse(r.reply().text().contains("已确认"));assertTrue(f.steps(r).stream().anyMatch(s->s.outcome().equals("REJECTED")));}),
            new AssistantEvaluationCases.Case("H08","查抢票进展不误拒绝为代购",true,f->{f.ownOrders();Run r=f.ask("不是让我买票，帮我看看我刚才抢票进展");f.used(r,"get_my_orders");assertEquals("ORDERS",r.reply().kind());assertEquals(2,r.reply().orders().size());assertTrue(r.reply().orders().stream().noneMatch(o->o.requestId().equals("secret")));})
        );
        return cases.stream().map(c->DynamicTest.dynamicTest(c.id()+" "+c.name(),()->batch.execute(c)));
    }
}
