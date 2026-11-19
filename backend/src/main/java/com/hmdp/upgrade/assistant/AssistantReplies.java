package com.hmdp.upgrade.assistant;

import java.time.Instant;
import java.util.*;
import static com.hmdp.upgrade.assistant.AssistantData.*;

public final class AssistantReplies {
    private AssistantReplies() {}
    public static Reply render(Checkpoint cp,Instant now) {
        if(cp.view.startsWith("LOCAL_")&&cp.localResult!=null){String kind=cp.localResult.path("kind").asText();String text=switch(kind){
            case "SHOPS" -> cp.localResult.path("data").path("shops").isEmpty()?"没有找到同时满足这些条件的小店。可以明确调整预算、距离或分类后再查。":"按你的条件找到这些小店。人均和评分来自当前店铺信息，距离为直线距离，不能据此判断等位时间。";
            case "COMPARE" -> "以下按同一组公开信息比较这些店，未提供的字段暂无法确认。";
            case "SHOP_COUPONS" -> "这是刚查询到的优惠券。是否可领取仍以点击领取时的校验为准，当前没有替你领券。";
            case "MY_COUPONS" -> "以下仅展示当前账号的券包。优惠券有效期和使用状态以服务端查询为准。";
            case "PAYMENT" -> cp.localResult.path("data").path("items").isEmpty()?"当前请求没有付款记录，不能据此认定已付款或已退款。":"已查询本人付款和退款记录。只有退款完成时间存在，才表示模拟退款已完成。";
            case "POLICIES" -> cp.localResult.path("data").path("items").isEmpty()?"已发布的帮助规则中暂未找到依据。可以说明具体问题，或填写反馈。":"以下是已发布的规则及版本。未写明的内容暂无法确认。";
            default -> "已整理为反馈草稿，尚未提交。请检查内容，再点击确认提交。";
        };List<String> sources=new ArrayList<>();cp.localResult.path("sources").forEach(x->sources.add(x.asText()));return new Reply(cp.view,text,List.of(),List.of(),List.of(),sources,cp.evidenceAt,null,cp.localResult);}
        String text=switch(cp.view) {
            case "SEARCH" -> cp.activities.isEmpty()?"没有找到同时满足当前条件且有余票的活动。可以调整时间、预算或校区后再查。"
                :"按当前条件找到以下活动，已按"+(cp.state.criteria.sort.equals("price")?"价格":"活动时间")+"排序。余票以查询时为准，进入详情后仍需重新校验；当前没有为你占票。";
            case "DETAIL" -> cp.activities.isEmpty()?"暂未查到活动信息。":switch(cp.activities.get(0).saleStatus()) {
                case "SOLD_OUT" -> "这场活动查询时已售罄。可以打开活动详情查看候补，或让我按原条件寻找其他活动。";
                case "NOT_STARTED" -> "这场活动尚未开售，请留意下方开售时间。";
                case "ENDED" -> "这场活动的抢票时间已结束。";
                default -> "已重新查询这场活动的余票和价格。当前余票不代表已为你锁票，请进入活动详情确认购买。";};
            case "RULES" -> "以下是平台及所选活动已发布的规则。未列出的退改、入场细节暂无法确认，请咨询活动主办方。";
            case "ORDERS" -> cp.orders.isEmpty()?"暂未查到符合条件的本人抢票记录。可以打开票夹核实，或提供具体请求编号。":"以下是本人抢票记录的查询结果。请求受理、订单创建与支付确认分别展示，付款及退款请以付款记录为准。";
            case "UNSUPPORTED" -> "我可以帮你找活动、查余票、解释规则和查看本人订单。购票、付款或取消请前往活动详情及我的票夹确认操作。";
            default -> "请补充"+(cp.missing.isEmpty()?"想找什么活动，或想查询哪笔订单":String.join("、",cp.missing.stream().map(f->switch(f){case "time"->"参加时间";case "budget"->"预算";case "campus"->"校区";case "activity"->"具体活动（可说第几个）";default->"需要查询的内容";}).toList()))+"，我再继续查询。";
        };
        if(cp.view.equals("DETAIL")&&!cp.activities.isEmpty()&&cp.state.criteria.maxPriceCents!=null&&cp.activities.get(0).priceCents()>cp.state.criteria.maxPriceCents)
            text+=" 注意：当前价格已超过你设置的预算。";
        List<Activity> activities=List.of();List<Rule> rules=List.of();List<Order> orders=List.of();
        if(Set.of("SEARCH","DETAIL","RULES").contains(cp.view))activities=List.copyOf(cp.activities);
        if(cp.view.equals("RULES"))rules=List.copyOf(cp.rules);
        if(cp.view.equals("ORDERS"))orders=List.copyOf(cp.orders);
        Set<String> sources=new LinkedHashSet<>();activities.forEach(a->sources.add(a.sourcePath()));rules.forEach(r->sources.add(r.sourcePath()));orders.forEach(o->sources.add(o.sourcePath()));
        if(cp.view.equals("RULES")&&!activities.isEmpty())text+=" 同时保留本轮已查询的活动卡片；余票以卡片查询时为准，尚未锁票。";
        Instant checked=cp.evidenceAt==null?now:cp.evidenceAt;
        for(Activity a:activities)if(a.checkedAt().isBefore(checked))checked=a.checkedAt();
        for(Rule r:rules)if(r.checkedAt().isBefore(checked))checked=r.checkedAt();
        for(Order o:orders)if(o.checkedAt().isBefore(checked))checked=o.checkedAt();
        return new Reply(cp.view,text,activities,rules,orders,List.copyOf(sources),checked,null);
    }
    public static Reply failure(String code,Instant now) {
        String text=switch(code) {
            case "ASSISTANT_ACTIVITY_REQUIRED","ASSISTANT_UNKNOWN_CANDIDATE" -> "请说明要查询哪场活动，可以用上一轮列表中的序号；若活动已不在列表，请先重新搜索。";
            case "LOCAL_PARTY_SIZE_REQUIRED" -> "这是总预算，需要知道一共几个人，才能折算为人均参考预算。";
            case "LOCAL_SHOP_REQUIRED" -> "请先搜索店铺，或明确选择刚才列表中的哪一家。对比需要至少两家店。";
            case "LOCAL_PRICE_UNKNOWN" -> "该店没有提供人均价格，暂时不能据此寻找更便宜的店。";
            case "ASSISTANT_QUERY_TOO_BROAD" -> "检索范围较大，请缩小日期、校区或活动类型后再试。";
            case "ASSISTANT_ALREADY_FREE" -> "当前参考活动已是免费活动，无法再降低价格。可以调整时间、校区或活动类型。";
            case "REQUEST_NOT_FOUND" -> "未找到属于当前账号的这笔请求，请核对编号或打开我的票夹。";
            case "ACTIVITY_NOT_FOUND" -> "活动已下架或场次已过期，请重新搜索。";
            case "ASSISTANT_INVALID_FILTER" -> "时间或预算条件不正确，请提供有效日期、时间范围和非负预算。";
            case "ASSISTANT_CONSTRAINT_CHANGE" -> "没有继续放宽你的筛选条件。请明确调整预算、日期或活动类型后再查询。";
            case "ASSISTANT_BUDGET_EXCEEDED" -> "本次查询已达到执行次数或时间限制，请缩小问题范围后再试。";
            default -> "本次查询没有完成，暂时无法确认结果。可以稍后重试，或直接打开活动详情和我的票夹。";
        };
        return new Reply("ERROR",text,List.of(),List.of(),List.of(),List.of(),now,code);
    }
}
