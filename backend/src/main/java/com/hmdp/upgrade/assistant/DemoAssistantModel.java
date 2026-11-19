package com.hmdp.upgrade.assistant;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.*;
import static com.hmdp.upgrade.assistant.AssistantData.*;

/** Explicit deterministic fixture for local acceptance. Not an LLM or a measure of model quality. */
public final class DemoAssistantModel implements AssistantModel {
    private final ObjectMapper json;
    public DemoAssistantModel(ObjectMapper json){this.json=json;}
    public String mode(){return "stub";}
    private ModelAnswer call(String name,ObjectNode args) {
        ObjectNode m=json.createObjectNode().put("role","assistant");m.putNull("content");
        ObjectNode c=json.createObjectNode().put("id","demo_"+UUID.randomUUID().toString().replace("-","")).put("type","function");
        c.set("function",json.createObjectNode().put("name",name).put("arguments",args.toString()));m.putArray("tool_calls").add(c);
        return new ModelAnswer(m,0);
    }
    private ModelAnswer ask(String field){ObjectNode a=json.createObjectNode();a.putArray("fields").add(field);return call("ask_clarification",a);}
    private Set<String> called(Checkpoint cp) {
        Set<String> result=new HashSet<>();for(var m:cp.messages)for(var c:m.path("tool_calls"))result.add(c.path("function").path("name").asText());return result;
    }
    public ModelAnswer next(Checkpoint cp,String request,Instant now,long remaining) {
        Set<String> called=called(cp);String text=request.trim();
        if(cp.localEnabled&&called.isEmpty()){ModelAnswer local=local(cp,text);if(local!=null)return local;}
        if(!called.isEmpty()) {
            if(cp.localEnabled&&called.contains("get_my_orders")&&!called.contains("get_my_payment_status")&&text.matches(".*(退款进度|付款记录|退款状态|退了没).*")){
                if(cp.orders.size()==1)return call("get_my_payment_status",json.createObjectNode().put("requestId",cp.orders.get(0).requestId()));
                return ask("goal");
            }
            if(called.contains("get_activity")&&!called.contains("search_activities")&&text.matches(".*(没票就|售罄就|类似|换个).*")) {
                Activity selected=cp.state.candidates.stream().filter(a->a.activityId().equals(cp.state.selectedActivityId)).findFirst().orElse(null);
                if(selected!=null&&selected.saleStatus().equals("SOLD_OUT"))return call("search_activities",json.createObjectNode().put("excludeSelected",true));
            }
            ObjectNode stop=json.createObjectNode().put("role","assistant");stop.putNull("content");return new ModelAnswer(stop,0);
        }
        if(text.matches(".*(我的.*(订单|票|请求)|我.*抢到|刚才.*抢|订单状态|抢票结果|还有多久.*付|多久付款|还能付款).*")) {
            Matcher id=Pattern.compile("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}").matcher(text);
            if(id.find())return call("get_my_order",json.createObjectNode().put("requestId",id.group()));
            return call("get_my_orders",json.createObjectNode());
        }
        Integer position=position(text);
        ObjectNode target=json.createObjectNode();if(position!=null)target.put("position",position);
        if(text.matches(".*(规则|限购|几张|支付期限|入场|退票|退款|买几次|买几单|支付时间).*"))return call("get_rules",target);
        if(text.matches(".*(有票|余票|还有票|库存|开售|开票|详情|第二个怎么样|第一个怎么样).*")) {
            boolean searching=text.matches(".*(推荐|有哪些|有什么|找|预算|音乐活动|戏剧活动).*" );
            if(!searching) {
                if(position==null&&cp.state.selectedActivityId==null&&cp.state.candidates.size()!=1)return ask("activity");
                return call("get_activity",target);
            }
        }
        if(text.matches(".*(别跨校区|不要跨校区).*" )&&cp.state.criteria.campus==null)return ask("campus");
        ObjectNode patch=json.createObjectNode();LocalDate today=now.atZone(ZONE).toLocalDate();
        Matcher date=Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})").matcher(text);
        LocalDate from=null,to=null;
        if(date.find()) {try{from=LocalDate.parse(date.group());to=date.find()?LocalDate.parse(date.group()):from;}catch(RuntimeException bad){return ask("time");}}
        else if(text.contains("后天"))from=today.plusDays(2);
        else if(text.contains("明天"))from=today.plusDays(1);
        else if(text.contains("今天"))from=today;
        else {
            Matcher week=Pattern.compile("(下周|本周|这周|周|星期)([一二三四五六日天])").matcher(text);
            if(week.find()) {
                int day="一二三四五六日".indexOf(week.group(2).replace('天','日'))+1;
                from=week.group(1).equals("下周")?today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1).plusDays(day-1):
                    (week.group(1).equals("本周")||week.group(1).equals("这周"))?today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(day-1):today.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(day)));
            } else if(text.contains("周末")) {from=today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));to=from.plusDays(1);}
        }
        if(from!=null){patch.put("dateFrom",from.toString());patch.put("dateTo",(to==null?from:to).toString());}
        if(text.matches(".*(晚上|晚间).*")){patch.put("hourFrom",18);patch.put("hourTo",24);}
        if(text.matches(".*(下午|午后).*")){patch.put("hourFrom",12);patch.put("hourTo",18);}
        if(text.matches(".*(上午|早上).*")){patch.put("hourFrom",6);patch.put("hourTo",12);}
        if(text.matches(".*(全天|不限时间段).*")){patch.putNull("hourFrom");patch.putNull("hourTo");}
        Matcher hours=Pattern.compile("(\\d{1,2})(?:点|:00)\\s*(?:到|至|[-—])\\s*(\\d{1,2})(?:点|:00)").matcher(text);
        if(hours.find()){patch.put("hourFrom",Integer.parseInt(hours.group(1)));patch.put("hourTo",Integer.parseInt(hours.group(2)));}
        Matcher budget=Pattern.compile("(?:预算|不超过|最多|低于|小于|不高于|上限)[：:\\s]*(\\d+(?:\\.\\d{1,2})?)").matcher(text);
        if(!budget.find())budget=Pattern.compile("(\\d+(?:\\.\\d{1,2})?)\\s*(?:元|块)(?:以内|以下|内)?").matcher(text);
        else {patch.put("maxPriceCents",new java.math.BigDecimal(budget.group(1)).movePointRight(2).intValueExact());budget=null;}
        if(budget!=null&&budget.find(0))patch.put("maxPriceCents",new java.math.BigDecimal(budget.group(1)).movePointRight(2).intValueExact());
        if(text.contains("免费"))patch.put("maxPriceCents",0);
        if(text.matches(".*(不限预算|预算不限|不限价格).*"))patch.putNull("maxPriceCents");
        for(String campus:List.of("主校区","东校区","南校区","北校区","西校区"))if(text.contains(campus))patch.put("campus",campus);
        if(text.matches(".*(不限校区|哪里都行).*"))patch.putNull("campus");
        String[][] categories={{"音乐演出","音乐|演唱会|民谣"},{"戏剧舞台","话剧|戏剧|脱口秀"},{"校园赛事","比赛|篮球|辩论|赛事"},{"讲座论坛","讲座|论坛"},{"电影放映","电影|放映"},{"创意工坊","手作|工坊|烘焙"},{"展览市集","展览|市集"},{"运动体验","攀岩|运动"},{"社团活动","社团|桌游|天文"}};
        for(String[] cat:categories)if(text.matches(".*("+cat[1]+").*")){patch.put("category",cat[0]);break;}
        if(text.matches(".*(不限类型|全部类型).*"))patch.putNull("category");
        if(text.matches(".*(便宜一点|便宜点|更便宜|太贵).*"))patch.put("cheaper",true);
        if(text.matches(".*(价格优先|按价格|从便宜).*"))patch.put("sort","price");
        if(text.contains("重新开始")||text.contains("清空条件"))for(String f:List.of("dateFrom","dateTo","campus","category","query","maxPriceCents","hourFrom","hourTo"))patch.putNull(f);
        if(patch.isEmpty()&&cp.state.candidates.isEmpty()&&!text.matches(".*(推荐|活动|看看|找|演出).*"))return ask("goal");
        return call("search_activities",patch);
    }
    private ModelAnswer local(Checkpoint cp,String text){
        if(text.matches(".*(反馈|投诉).*")&&!text.matches(".*(怎么|如何|规则|为什么).*"))return call("prepare_support_draft",json.createObjectNode().put("title","校园生活体验反馈").put("body",text.length()>=10?text:"我想反馈一个校园生活平台使用问题："+text));
        if(text.matches(".*(退款进度|付款记录|退款状态|退了没).*")){
            Matcher id=Pattern.compile("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}").matcher(text);
            return id.find()?call("get_my_payment_status",json.createObjectNode().put("requestId",id.group())):call("get_my_orders",json.createObjectNode());
        }
        if(text.matches(".*(我的券|我的优惠券|券包).*"))return call("get_my_coupons",json.createObjectNode());
        if(text.matches(".*(怎么|为什么|规则|如何|忘记|能不能|可以|过期).*")&&text.matches(".*(券|签到|密码|恢复码|核销|评论|评价|打卡|退款|取消).*"))return call("search_policies",json.createObjectNode().put("q",text));
        if(text.matches(".*(对比|比较|比一比).*")){if(cp.state.shopCandidates.size()<2)return ask("goal");return call("compare_shops",json.createObjectNode());}
        if(!cp.state.shopCandidates.isEmpty()&&text.matches(".*(优惠券|有券|领券|优惠).*")) {var target=json.createObjectNode();Integer pos=position(text);if(pos!=null)target.put("position",pos);return call("get_shop_coupons",target);}
        if(!cp.state.shopCandidates.isEmpty()&&text.matches(".*(第.个|这家|店铺详情).*")){var target=json.createObjectNode();Integer pos=position(text);if(pos!=null)target.put("position",pos);return call("get_shop",target);}
        boolean follow=!cp.state.shopCandidates.isEmpty()&&text.matches(".*(便宜|换成|预算|校区|不变|不限|有券|近一点).*"),shop=text.matches(".*(找店|选店|小店|店铺|商铺|吃|咖啡|火锅|甜品|烘焙|聚餐|茶饮).*" );
        if(!shop&&!follow)return null;
        var a=json.createObjectNode();String[][] cats={{"咖啡茶饮","咖啡|茶饮"},{"火锅","火锅"},{"甜品烘焙","甜品|烘焙|蛋糕"},{"休闲娱乐","桌游|自习|娱乐"},{"运动体验","运动|攀岩|瑜伽"},{"美食","美食|吃|聚餐"}};
        for(var cat:cats)if(text.matches(".*("+cat[1]+").*")){a.put("category",cat[0]);break;}
        for(String campus:List.of("主校区","东校区","南校区"))if(text.contains(campus)){a.put("campus",campus);a.put("landmark",campus.equals("主校区")?"main-east":campus.equals("东校区")?"east-gate":"south-gate");}
        Matcher budget=Pattern.compile("(?:预算|不超过|最多|人均)[：: ]*([0-9]+)").matcher(text);if(budget.find())a.put("maxPriceCents",Integer.parseInt(budget.group(1))*100);
        Matcher distance=Pattern.compile("([1-9][0-9]?)公里").matcher(text);if(distance.find())a.put("maxDistanceMeters",Integer.parseInt(distance.group(1))*1000);
        Matcher total=Pattern.compile("(?:一共|总预算|总共)[：: ]*([0-9]+)").matcher(text);Matcher people=Pattern.compile("([1-9][0-9]?)个?人").matcher(text);if(total.find()&&!text.contains("人均")){a.put("totalBudgetCents",Integer.parseInt(total.group(1))*100);if(people.find())a.put("people",Integer.parseInt(people.group(1)));}
        if(text.contains("营业"))a.put("openOnly",true);if(text.contains("有优惠")||text.contains("有券"))a.put("hasCoupon",true);
        if(text.contains("安静"))a.put("q","安静");if(text.contains("不限预算"))a.putNull("maxPriceCents");if(text.contains("不限校区"))a.putNull("campus");if(text.contains("不限类型"))a.putNull("category");
        if(text.matches(".*(更便宜|便宜点|便宜一点).*"))a.put("cheaper",true);if(text.contains("近一点"))a.put("sort","distance");
        return call("search_shops",a);
    }
    private Integer position(String text) {
        Matcher m=Pattern.compile("第([一二三四五六七八12345678])(?:个|场|项)?").matcher(text);
        if(!m.find())return null;String s=m.group(1);return Character.isDigit(s.charAt(0))?Integer.parseInt(s):"一二三四五六七八".indexOf(s)+1;
    }
}
