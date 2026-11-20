package com.hmdp.upgrade.assistant;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.upgrade.Problem;
import com.hmdp.upgrade.CustomerRequests;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static com.hmdp.upgrade.assistant.AssistantData.*;

@Service
public class AssistantTools {
    private final AssistantCatalog catalog;
    private final AssistantOrders orders;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.hmdp.upgrade.LocalAssistantTools local;
    public boolean localEnabled(){return local!=null&&local.enabled();}
    public Object localContext(long user){return localEnabled()?local.context(user):Map.of();}
    public boolean allowed(String name){return ALLOWED.contains(name)||(localEnabled()&&com.hmdp.upgrade.LocalAssistantTools.NAMES.contains(name));}
    public AssistantTools(AssistantCatalog catalog,AssistantOrders orders,ObjectMapper json) {this.catalog=catalog;this.orders=orders;this.json=json;}
    public static final Set<String> ALLOWED=Set.of("search_activities","get_activity","get_rules","get_my_orders","get_my_order","ask_clarification");
    private static final Set<String> FILTERS=Set.of("dateFrom","dateTo","campus","category","query","maxPriceCents","hourFrom","hourTo","sort");
    static void fields(JsonNode node,Set<String> allowed) {
        if(node==null||!node.isObject()||node.size()>15)throw new Problem(400,"ASSISTANT_INVALID_ARGUMENTS");
        node.fieldNames().forEachRemaining(name->{if(!allowed.contains(name))throw new Problem(400,"ASSISTANT_INVALID_ARGUMENTS");});
    }
    private String text(JsonNode node,String key,int max) {
        JsonNode value=node.get(key);if(value==null||value.isNull())return null;
        if(!value.isTextual()||value.textValue().length()>max)throw new Problem(400,"ASSISTANT_INVALID_ARGUMENTS");return value.textValue();
    }
    private String target(JsonNode args,State state,boolean required) {
        String id=text(args,"activityId",24);
        if(args.hasNonNull("position")) {
            if(id!=null||!args.get("position").isIntegralNumber()||!args.get("position").canConvertToInt())throw new Problem(400,"ASSISTANT_INVALID_ARGUMENTS");
            int pos=args.get("position").asInt();
            if(pos<1||pos>state.candidates.size())throw new Problem(400,"ASSISTANT_ACTIVITY_REQUIRED");
            id=state.candidates.get(pos-1).activityId();
        }
        if(id==null)id=state.selectedActivityId;
        if(id==null&&state.candidates.size()==1)id=state.candidates.get(0).activityId();
        if(id==null) {if(required)throw new Problem(400,"ASSISTANT_ACTIVITY_REQUIRED");return null;}
        final String selected=id;
        if(!id.equals(state.selectedActivityId)&&state.candidates.stream().noneMatch(a->a.activityId().equals(selected)))
            throw new Problem(400,"ASSISTANT_UNKNOWN_CANDIDATE");
        return id;
    }
    public JsonNode execute(String name,JsonNode args,long user,Checkpoint cp) {
        if(localEnabled()&&com.hmdp.upgrade.LocalAssistantTools.NAMES.contains(name))return local.execute(name,args,user,cp);
        if(!ALLOWED.contains(name))throw new Problem(403,"ASSISTANT_TOOL_FORBIDDEN");
        switch(name) {
            case "search_activities" -> {
                Set<String> allowed=new HashSet<>(FILTERS);allowed.addAll(Set.of("cheaper","excludeSelected"));fields(args,allowed);
                ObjectNode merged=json.valueToTree(cp.state.criteria);
                for(String field:FILTERS) if(args.has(field)) {
                    JsonNode v=args.get(field);
                    if(!v.isNull() && (Set.of("maxPriceCents","hourFrom","hourTo").contains(field)?(!v.isIntegralNumber()||!v.canConvertToInt()):!v.isTextual()))throw new Problem(400,"ASSISTANT_INVALID_ARGUMENTS");
                    merged.set(field,v);
                }
                // A new single date must also replace a previous date range.
                if(args.has("dateFrom")&&!args.has("dateTo"))merged.set("dateTo",args.get("dateFrom"));
                for(String flag:List.of("cheaper","excludeSelected"))if(args.has(flag)&&!args.get(flag).isBoolean())throw new Problem(400,"ASSISTANT_INVALID_ARGUMENTS");
                Criteria c=json.convertValue(merged,Criteria.class);if(c.sort==null)c.sort="time";
                if(args.path("cheaper").asBoolean()) {
                    Activity selected=cp.state.candidates.stream().filter(a->a.activityId().equals(cp.state.selectedActivityId)).findFirst().orElse(null);
                    if(selected==null&&cp.state.candidates.isEmpty())throw new Problem(400,"ASSISTANT_ACTIVITY_REQUIRED");
                    int price=selected!=null?selected.priceCents():cp.state.candidates.stream().mapToInt(Activity::priceCents).min().orElse(0);
                    if(price==0)throw new Problem(400,"ASSISTANT_ALREADY_FREE");
                    c.maxPriceCents=Math.min(c.maxPriceCents==null?Integer.MAX_VALUE:c.maxPriceCents,Math.max(0,price-1));c.sort="price";
                }
                // One user turn cannot silently change constraints after observing its first search result.
                // A subsequent user turn gets a fresh checkpoint and may explicitly modify/clear them.
                if(cp.searchGuard!=null) {
                    ObjectNode original=json.valueToTree(cp.searchGuard),proposed=json.valueToTree(c);
                    for(String field:FILTERS)if(!field.equals("sort")&&!Objects.equals(original.get(field),proposed.get(field)))
                        throw new Problem(400,"ASSISTANT_CONSTRAINT_CHANGE");
                }
                var found=catalog.search(c,args.path("excludeSelected").asBoolean()?cp.state.selectedActivityId:null);
                if(cp.searchGuard==null)cp.searchGuard=json.convertValue(json.valueToTree(c),Criteria.class);
                cp.state.criteria=c;cp.state.candidates=new ArrayList<>(found);cp.activities=new ArrayList<>(found);cp.view="SEARCH";
                cp.evidenceAt=found.isEmpty()?catalog.now():found.get(0).checkedAt();
                if(found.stream().noneMatch(a->a.activityId().equals(cp.state.selectedActivityId)))cp.state.selectedActivityId=null;
                return json.valueToTree(Map.of("criteria",c,"activities",found,"note","余票是查询时的数据库快照，提交抢票时重新校验；未预留票额"));
            }
            case "get_activity" -> {
                fields(args,Set.of("activityId","position"));String id=target(args,cp.state,true);
                Activity activity=catalog.detail(id);cp.state.selectedActivityId=id;cp.activities=new ArrayList<>(List.of(activity));cp.view="DETAIL";
                cp.evidenceAt=activity.checkedAt();
                // The last displayed list now contains this one card, so later '第一个' refers to this activity.
                cp.state.candidates=new ArrayList<>(List.of(activity));
                return json.valueToTree(activity);
            }
            case "get_rules" -> {
                fields(args,Set.of("activityId","position"));String id=target(args,cp.state,false);
                cp.rules=new ArrayList<>(catalog.rules(id));cp.view="RULES";cp.evidenceAt=cp.rules.get(0).checkedAt();return json.valueToTree(cp.rules);
            }
            case "get_my_orders" -> {
                fields(args,Set.of("state"));String filter=Optional.ofNullable(text(args,"state",12)).orElse("");
                if(!List.of("","pending","confirmed","closed").contains(filter))throw new Problem(400,"ASSISTANT_INVALID_ARGUMENTS");
                var result=orders.list(user,filter);cp.orders=result.items().stream().limit(10).map(item->order(item,result.serverTime())).toList();cp.view="ORDERS";
                cp.evidenceAt=result.serverTime();
                return json.valueToTree(Map.of("items",cp.orders,"total",result.total(),"note","仅展示本人最近10条；未查询支付流水，不能据此声称已退款"));
            }
            case "get_my_order" -> {
                fields(args,Set.of("requestId"));String id=text(args,"requestId",36);
                if(id==null||!id.matches("[A-Za-z0-9_-]{1,36}"))throw new Problem(400,"ASSISTANT_INVALID_ARGUMENTS");
                var result=orders.detail(user,id);Order detail=order(result.item(),result.serverTime());
                Map<String,Order> merged=new LinkedHashMap<>();cp.orders.forEach(item->merged.put(item.requestId(),item));merged.put(detail.requestId(),detail);
                cp.orders=List.copyOf(merged.values());cp.view="ORDERS";
                cp.evidenceAt=result.serverTime();
                return json.valueToTree(cp.orders);
            }
            case "ask_clarification" -> {
                fields(args,Set.of("fields"));JsonNode requested=args.path("fields");
                if(!requested.isArray()||requested.isEmpty()||requested.size()>4)throw new Problem(400,"ASSISTANT_INVALID_ARGUMENTS");
                List<String> missing=new ArrayList<>();for(JsonNode f:requested) {
                    if(!f.isTextual()||!Set.of("time","budget","campus","activity","goal").contains(f.asText()))throw new Problem(400,"ASSISTANT_INVALID_ARGUMENTS");missing.add(f.asText());
                }
                cp.missing=missing;cp.view="CLARIFY";return json.valueToTree(Map.of("fields",missing));
            }
            default -> throw new Problem(403,"ASSISTANT_TOOL_FORBIDDEN");
        }
    }
    private Order order(CustomerRequests.Item item,Instant now) {
        String state=item.orderState()==null?item.state():item.orderState();
        String message=switch(state) {
            case "ACCEPTED" -> "抢票请求已受理，仍在处理，尚不能确认已生成订单。";
            case "PENDING_CONFIRM" -> item.confirmUntil()!=null&&now.isBefore(item.confirmUntil())
                ?"订单已创建，等待支付；请在截止时间前前往票夹处理。":"支付时间已到，订单仍待后台核对；请刷新票夹，当前不能承诺仍可支付。";
            case "CONFIRMED" -> "订单已确认。请按活动发布的入场要求准备。";
            case "CANCELLED" -> "订单已取消；付款和退款结果请查看票夹中的付款记录。";
            case "EXPIRED" -> "请求或订单已超时关闭；付款和退款结果请查看付款记录。";
            case "REJECTED" -> "抢票未成功。"+switch(Objects.toString(item.reason(),"")) {
                case "SOLD_OUT" -> "原因：余票不足。";case "ALREADY_PURCHASED" -> "原因：该活动已经参与过，不能重复购买。";default -> "请前往票夹查看处理结果。";};
            default -> "状态暂无法解释，请前往票夹核实。";
        };
        return new Order(item.id(),Objects.toString(item.shopName(),"活动订单"),item.state(),item.orderState(),message,item.confirmUntil(),now,"/v2/account/requests/"+item.id());
    }
}
