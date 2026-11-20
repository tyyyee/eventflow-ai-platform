package com.hmdp.upgrade;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.upgrade.assistant.DemoAssistantModel;
import com.hmdp.upgrade.assistant.AssistantData.Checkpoint;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** G05: a fixed 20/15/10/10/5 corpus. Stub routing + real domain reads, not an LLM quality score. */
class LocalAgentAcceptanceTest {
    @TempDir Path files;
    static final List<Map<String,Object>> evidence=new ArrayList<>();
    record Call(String tool,JsonNode args){}
    LocalLifeTest fixture(){var f=new LocalLifeTest();f.files=files;f.setup();return f;}
    Call route(LocalLifeTest f,Checkpoint cp,String input)throws Exception{
        cp.localEnabled=true;var answer=new DemoAssistantModel(f.json).next(cp,input,Instant.now(),10000);
        var call=answer.message().path("tool_calls").get(0).path("function");
        return new Call(call.path("name").asText(),f.json.readTree(call.path("arguments").asText()));
    }
    JsonNode execute(LocalLifeTest f,Checkpoint cp,String input,String expected)throws Exception{var call=route(f,cp,input);assertEquals(expected,call.tool);return f.agent.execute(call.tool,call.args,1,cp);}
    @TestFactory List<DynamicTest> corpus(){var tests=new ArrayList<DynamicTest>();
        // 20 choices include exact-price boundaries, empty categories and campus intersections.
        String[] categories={"咖啡","火锅","甜品","美食"};int[] budgets={0,29,30,31,50};
        for(int i=0;i<20;i++){final int n=i;String input="主校区人均"+budgets[i%5]+"元，推荐"+categories[i/5]+"店铺";
            add(tests,"S"+(i+1),"选店",input,"search_shops","仅主校区，价格不超预算，空结果不放宽条件",f->{var cp=new Checkpoint();var result=execute(f,cp,input,"search_shops");assertEquals(n/5==0&&budgets[n%5]>=30?1:0,result.path("data").path("shops").size());assertEquals("主校区",cp.state.shopFilters.get("campus"));assertEquals(Integer.toString(budgets[n%5]*100),cp.state.shopFilters.get("maxPriceCents"));});}
        String[] questions={"优惠券过期还能用吗","怎么核销优惠券","签到规则是什么","忘记密码怎么办","评价可以修改吗"};
        for(int i=0;i<15;i++){final int n=i;String input=questions[i%5],state=List.of("PUBLISHED","DRAFT","ARCHIVED").get(i/5);
            add(tests,"R"+(i+1),"客服规则",input,"search_policies","只引用已发布规则，草稿和归档不得作为答案",f->{String keyword=List.of("过期","核销","签到","密码","评价").get(n%5);f.ops.savePolicy(3,null,Map.of("title",input,"category","帮助","keywords",keyword,"body","固定验收规则：该信息以当前已发布规则及本人业务记录为准。","state",state));var result=execute(f,new Checkpoint(),input,"search_policies");assertEquals(state.equals("PUBLISHED")?1:0,result.path("data").path("items").size());assertFalse(result.toString().contains("已退款"));});}
        for(int i=0;i<10;i++){final int n=i;
            add(tests,"I"+(i+1),"本人数据与越权",i<4?"看看我的券包":"拒绝指定他人身份或私有记录","get_my_coupons / get_my_payment_status / get_shop","不得返回他人券、核销码、支付记录或未查询店铺",f->{
                var c=f.coupon(1,f.template(2));var cp=new Checkpoint();
                if(n<4){String state=List.of("AVAILABLE","USED","VOID","EXPIRED").get(n);if(n==3)f.db.update("UPDATE ux_user_coupon SET valid_until=? WHERE id=?",LocalData.at(Instant.now().minusSeconds(1)),c.get("id"));else f.db.update("UPDATE ux_user_coupon SET state=? WHERE id=?",state,c.get("id"));var result=execute(f,cp,"看看我的券包","get_my_coupons");assertEquals(state,result.path("data").path("items").get(0).path("state").asText());assertFalse(result.toString().contains(c.get("redeemCode").toString()));}
                else if(n==4)assertTrue(f.agent.execute("get_my_coupons",f.json.createObjectNode(),2,cp).path("data").path("items").isEmpty());
                else if(n==5)assertThrows(Problem.class,()->f.agent.execute("get_my_coupons",f.json.createObjectNode().put("userId",2),1,cp));
                else if(n==6)assertThrows(Problem.class,()->f.agent.execute("get_my_payment_status",f.json.createObjectNode().put("requestId",UUID.randomUUID().toString()),1,cp));
                else if(n==7)assertThrows(Problem.class,()->f.agent.execute("get_my_payment_status",f.json.createObjectNode().put("userId",2),1,cp));
                else if(n==8)assertThrows(Problem.class,()->f.agent.execute("get_shop",f.json.createObjectNode().put("shopId","1"),1,cp));
                else assertThrows(Problem.class,()->f.agent.execute("execute_sql",f.json.createObjectNode().put("sql","SELECT * FROM ux_user_coupon"),1,cp));
            });}
        String[] followups={"换成东校区的，其他条件不变","换成南校区的，其他条件不变","预算35元，其他条件不变","不限预算，继续找店","找店，不限校区","推荐火锅店，其他条件不变","1公里内找咖啡店","更便宜一点","第一个店铺详情","比较这几家店"};
        for(int i=0;i<10;i++){final int n=i;String input=followups[i];
            add(tests,"M"+(i+1),"多轮与歧义","主校区预算50元推荐咖啡店 → "+input,i<8?"search_shops":i==8?"get_shop":"ask_clarification","保留未修改的条件，候选不足不能虚构比较结果",f->{
                var first=new Checkpoint();execute(f,first,"主校区预算50元推荐咖啡店","search_shops");var next=new Checkpoint();next.state=first.state;var call=route(f,next,input);
                assertEquals(n<8?"search_shops":n==8?"get_shop":"ask_clarification",call.tool);
                if(n==9){assertEquals(1,next.state.shopCandidates.size());return;}
                var result=f.agent.execute(call.tool,call.args,1,next);assertNotNull(result);
                if(n==0||n==1){assertEquals(n==0?"东校区":"南校区",next.state.shopFilters.get("campus"));assertTrue(result.path("data").path("shops").isEmpty());}
                else if(n==2)assertEquals("3500",next.state.shopFilters.get("maxPriceCents"));
                else if(n==3)assertFalse(next.state.shopFilters.containsKey("maxPriceCents"));
                else if(n==4)assertFalse(next.state.shopFilters.containsKey("campus"));
                else if(n==5)assertEquals("火锅",next.state.shopFilters.get("category"));
                else if(n==6)assertEquals("1000",next.state.shopFilters.get("maxDistanceMeters"));
                else if(n==7){assertEquals("2999",next.state.shopFilters.get("maxPriceCents"));assertTrue(result.path("data").path("shops").isEmpty());}
                else assertEquals("1",result.path("data").path("shops").get(0).path("id").asText());
                if(n!=2&&n!=3&&n!=7)assertEquals("5000",next.state.shopFilters.get("maxPriceCents"));
            });}
        for(int i=0;i<5;i++){final int n=i;
            add(tests,"F"+(i+1),"失败与恢复",List.of("查询结果后店铺下架","序列化后恢复候选与条件","草稿提交响应丢失后重试","模型尝试同轮放宽预算","查询失败后恢复服务").get(i),"受保护查询 / 幂等重试","错误明确返回；恢复不重复写入、不伪造结果",f->{
                var cp=new Checkpoint();execute(f,cp,"主校区预算50元推荐咖啡店","search_shops");
                if(n==0){f.db.update("UPDATE ux_local_shop SET status='CLOSED' WHERE shop_id=1");assertThrows(Problem.class,()->f.agent.execute("get_shop",f.json.createObjectNode().put("position",1),1,cp));}
                else if(n==1){var restored=f.json.readValue(f.json.writeValueAsString(cp),Checkpoint.class);assertEquals(cp.state.shopFilters,restored.state.shopFilters);assertEquals("1",f.agent.execute("get_shop",f.json.createObjectNode().put("position",1),1,restored).path("data").path("shops").get(0).path("id").asText());}
                else if(n==2){var draft=f.agent.execute("prepare_support_draft",f.json.createObjectNode().put("title","核对营业时间").put("body","我发现店铺页面的营业时间需要核对，请工作人员协助。"),1,new Checkpoint()).path("data");var payload=Map.<String,Object>of("requestKey",draft.path("draftId").asText(),"title",draft.path("title").asText(),"body",draft.path("body").asText());assertEquals(f.map(f.ops.feedback(1,payload)).get("id"),f.map(f.ops.feedback(1,payload)).get("id"));assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM ux_feedback",Integer.class));}
                else if(n==3)assertThrows(Problem.class,()->f.agent.execute("search_shops",f.json.createObjectNode().put("maxPriceCents",10000),1,cp));
                else{var spy=org.mockito.Mockito.spy(f.db);org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("isolated database failure")).when(spy).queryForObject(org.mockito.ArgumentMatchers.eq("SELECT CURRENT_TIMESTAMP(3)"),org.mockito.ArgumentMatchers.eq(java.sql.Timestamp.class));var broken=new LocalAssistantTools(f.config,new LocalShops(spy,f.tx,f.json,f.config,f.accounts),f.coupons,f.ops,spy,f.tx,f.json);assertThrows(org.springframework.dao.DataAccessException.class,()->broken.execute("search_shops",f.json.createObjectNode(),1,new Checkpoint()));assertEquals(1,execute(f,new Checkpoint(),"预算50元推荐咖啡店","search_shops").path("data").path("shops").size());}
            });}
        assertEquals(60,tests.size());return tests;
    }
    interface Check {void run(LocalLifeTest f)throws Exception;}
    void add(List<DynamicTest> tests,String id,String group,String input,String tool,String invariant,Check check){tests.add(DynamicTest.dynamicTest(id+" "+input,()->{var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("group",group);row.put("input",input);row.put("expectedTool",tool);row.put("invariant",invariant);try{check.run(fixture());row.put("passed",true);}catch(Throwable e){row.put("passed",false);throw e;}finally{evidence.add(row);}}));}
    @AfterAll static void report()throws Exception{Path output=Path.of("../docs/verification/2026-11-19-local-life/agent-corpus.json");Files.createDirectories(output.getParent());new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),Map.of("mode","stub + domain tools; no external LLM","fixture","one published main-campus coffee shop, price 3000 cents; isolated H2 database per case; case-specific coupons and policies","cases",evidence));}
}
