package com.hmdp.upgrade.assistant;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.upgrade.Problem;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.hmdp.upgrade.assistant.AssistantData.*;

/** Native function calls; no model-generated code, SQL or URLs are executed. */
public final class DeepSeekAssistantModel implements AssistantModel {
    private final ObjectMapper json;
    private final String key,endpoint,model;
    public DeepSeekAssistantModel(ObjectMapper json,String key,String base,String model,boolean localContractTest) {
        this.json=json;this.key=key;this.model=model;
        URI uri=URI.create(base);
        boolean local=localContractTest&&"http".equals(uri.getScheme())&&Set.of("localhost","127.0.0.1").contains(uri.getHost());
        if(key.isBlank()||(!"https".equals(uri.getScheme())&&!local)||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)
            throw new IllegalArgumentException("DeepSeek requires an API key and a trusted HTTPS base URL");
        if(!model.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,99}"))throw new IllegalArgumentException("Invalid assistant model");
        endpoint=base.replaceAll("/+$","")+"/chat/completions";
    }
    public String mode(){return "deepseek";}
    public ModelAnswer next(Checkpoint cp,String request,Instant now,long remaining) {
        try {
            if(json.writeValueAsBytes(cp.messages).length>32768)throw new Problem(400,"ASSISTANT_CONTEXT_LIMIT");
            var factory=new SimpleClientHttpRequestFactory() {
                @Override protected void prepareConnection(java.net.HttpURLConnection c,String method)throws java.io.IOException {super.prepareConnection(c,method);c.setInstanceFollowRedirects(false);}
            };
            factory.setConnectTimeout((int)Math.min(2000,Math.max(1,remaining)));
            factory.setReadTimeout((int)Math.min(8000,Math.max(1,remaining-1000)));
            byte[] bytes=RestClient.builder().requestFactory(factory).build().post().uri(endpoint)
                .header("Authorization","Bearer "+key).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("model",model,"messages",cp.messages,"tools",schemas(json,cp.localEnabled),"tool_choice","auto",
                    "max_tokens",1000,"temperature",0,"thinking",Map.of("type","disabled")))
                .exchange((req,res)->{
                    if(!res.getStatusCode().is2xxSuccessful())throw new Problem(503,"ASSISTANT_MODEL_UNAVAILABLE");
                    return res.getBody().readNBytes(65537);
                });
            if(bytes==null||bytes.length>65536)throw new Problem(503,"ASSISTANT_MODEL_RESPONSE");
            JsonNode body=json.readTree(bytes),usage=body.path("usage").path("total_tokens");
            if(!usage.isIntegralNumber()||!usage.canConvertToInt()||usage.intValue()<0)throw new Problem(503,"ASSISTANT_USAGE_UNKNOWN");
            JsonNode choice=body.path("choices").path(0),message=choice.path("message");
            if(!Set.of("stop","tool_calls").contains(choice.path("finish_reason").asText()))throw new Problem(503,"ASSISTANT_MODEL_RESPONSE");
            ObjectNode clean=json.createObjectNode().put("role","assistant");clean.putNull("content");
            JsonNode calls=message.path("tool_calls");
            if(calls.isArray()&&!calls.isEmpty()) {
                if(calls.size()>4)throw new Problem(503,"ASSISTANT_MODEL_RESPONSE");
                Set<String> ids=new HashSet<>();
                for(JsonNode call:calls) {
                    String id=call.path("id").asText(),name=call.path("function").path("name").asText();
                    JsonNode args=call.path("function").path("arguments");
                    if(!id.matches("[A-Za-z0-9_-]{1,100}")||!ids.add(id)||!"function".equals(call.path("type").asText())
                        ||!name.matches("[a-z_]{1,48}")||!args.isTextual()||args.textValue().length()>4096)throw new Problem(503,"ASSISTANT_MODEL_RESPONSE");
                }
                clean.set("tool_calls",calls.deepCopy());
            }
            // Natural-language output is intentionally not used as a source of business facts.
            return new ModelAnswer(clean,usage.intValue());
        } catch(Problem known){throw known;}
        catch(Exception failed){throw new Problem(503,"ASSISTANT_MODEL_UNAVAILABLE");}
    }
    public static List<Map<String,Object>> schemas(ObjectMapper json) {
        try {
            JsonNode filter=json.readTree("""
                {"dateFrom":{"type":["string","null"],"description":"Shanghai event date YYYY-MM-DD. Set dateTo as well when changing dates."},
                 "dateTo":{"type":["string","null"],"description":"Inclusive event end date, at most 30 days after dateFrom (31 calendar dates inclusive)"},
                 "campus":{"type":["string","null"]},"category":{"type":["string","null"],"description":"音乐演出/戏剧舞台/校园赛事/讲座论坛/社团活动/展览市集/运动体验/电影放映/创意工坊"},
                 "query":{"type":["string","null"]},"maxPriceCents":{"type":["integer","null"],"minimum":0},
                 "hourFrom":{"type":["integer","null"],"minimum":0,"maximum":23},"hourTo":{"type":["integer","null"],"minimum":1,"maximum":24},
                 "sort":{"type":"string","enum":["time","price"]},"cheaper":{"type":"boolean"},"excludeSelected":{"type":"boolean"}}
                """);
            Map<String,Object> target=Map.of("activityId",Map.of("type","string","description","ID from current candidates only"),"position",Map.of("type","integer","minimum",1,"maximum",8));
            return List.of(
                schema("search_activities","Search currently linked purchasable campus sessions. Arguments PATCH retained criteria; null clears a filter. Preserve constraints unless user changes them. cheaper means strictly cheaper than selected/previous lowest price. excludeSelected omits the selected activity.",json.convertValue(filter,Map.class),List.of()),
                schema("get_activity","Refresh current price, remaining tickets and sale window of a previously shown activity. Resolve 第几个 with position. Does not hold tickets.",target,List.of()),
                schema("get_rules","Read published platform and selected activity rules. Omit target for common rules. Never infer unlisted refund policy.",target,List.of()),
                schema("get_my_orders","Read only the signed-in user's recent requests/orders. No userId argument is permitted.",Map.of("state",Map.of("type","string","enum",List.of("","pending","confirmed","closed"))),List.of()),
                schema("get_my_order","Read one request belonging to signed-in user, by requestId, not orderId. Unknown ownership returns not found.",Map.of("requestId",Map.of("type","string")),List.of("requestId")),
                schema("ask_clarification","Ask only for information genuinely required to proceed, or clarify an ambiguous activity reference. End the turn after this.",Map.of("fields",Map.of("type","array","items",Map.of("type","string","enum",List.of("time","budget","campus","activity","goal")))),List.of("fields")));
        } catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    public static List<Map<String,Object>> schemas(ObjectMapper json,boolean local){
        var result=new ArrayList<>(schemas(json));if(!local)return result;
        Map<String,Object> text=Map.of("type","string"),number=Map.of("type","integer","minimum",0);
        var filters=new LinkedHashMap<String,Object>();for(String k:List.of("q","category","campus","area","landmark","sort"))filters.put(k,Map.of("type",List.of("string","null")));
        for(String k:List.of("maxPriceCents","maxDistanceMeters","minRating","totalBudgetCents","people"))filters.put(k,Map.of("type",List.of("number","null"),"minimum",0));
        for(String k:List.of("openOnly","hasCoupon","cheaper"))filters.put(k,Map.of("type","boolean"));
        result.add(schema("search_shops","Search campus local shops. PATCH retained shopFilters; null clears. category: 美食/咖啡茶饮/火锅/甜品烘焙/休闲娱乐/运动体验. campus 主校区/东校区/南校区. landmark main-east/main-library/east-gate/east-hall/south-gate/south-art. sort recommended/rating/distance/price. maxPriceCents is per-person cents. For TOTAL budget use totalBudgetCents and people; server divides. Never divide explicit per-person budget. Ask for party size when missing. No silent relaxation.",filters,List.of()));
        var target=Map.<String,Object>of("shopId",text,"position",Map.of("type","integer","minimum",1,"maximum",3));
        result.add(schema("get_shop","Read fresh details of a shop from last shopCandidates; no invented IDs.",target,List.of()));
        result.add(schema("get_shop_coupons","Read current published coupon templates of a previously shown shop. Does not claim coupons.",target,List.of()));
        result.add(schema("compare_shops","Compare 2 or 3 shops from last candidates; omit positions to compare the displayed list.",Map.of("positions",Map.of("type","array","items",Map.of("type","integer","minimum",1,"maximum",3),"minItems",2,"maxItems",3)),List.of()));
        result.add(schema("get_my_coupons","Read current user's wallet, no redemption codes. No userId accepted.",Map.of(),List.of()));
        result.add(schema("get_my_payment_status","Read current user's payment receipts and refund completion by requestId. Does not create payment or trigger refunds.",Map.of("requestId",text),List.of("requestId")));
        result.add(schema("search_policies","Find published versioned help rules by question/keywords. No results means no evidence.",Map.of("q",text),List.of("q")));
        result.add(schema("prepare_support_draft","Prepare a feedback draft from user's description. Does NOT submit. Never include secrets or claim resolution.",Map.of("title",text,"body",text),List.of("title","body")));
        return result;
    }
    private static Map<String,Object> schema(String name,String description,Map<String,Object> properties,List<String> required) {
        return Map.of("type","function","function",Map.of("name",name,"description",description,"parameters",
            Map.of("type","object","properties",properties,"required",required,"additionalProperties",false)));
    }
}
