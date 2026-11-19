package com.hmdp.upgrade.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;

/** Public facts are constructed from business reads, never from model prose. */
public final class AssistantData {
    private AssistantData() {}
    public static final java.time.ZoneId ZONE=java.time.ZoneId.of("Asia/Shanghai");
    public static class Criteria {
        public String dateFrom, dateTo, campus, category, query;
        public Integer maxPriceCents, hourFrom, hourTo;
        public String sort="time";
    }
    public record Activity(String activityId,String sessionId,String shopId,String title,String sessionTitle,
        String category,String campus,String address,Instant startsAt,Instant endsAt,int priceCents,int available,
        Instant saleStartsAt,Instant saleEndsAt,String saleStatus,Instant checkedAt,String sourcePath) {}
    public record Rule(String activityId,String title,String terms,String notice,String sourcePath,Instant checkedAt) {}
    public record Order(String requestId,String title,String requestState,String orderState,String description,
        Instant confirmUntil,Instant checkedAt,String sourcePath) {}
    public record Reply(String kind,String text,List<Activity> activities,List<Rule> rules,List<Order> orders,
        List<String> sources,Instant checkedAt,String errorCode) {}
    public static class State {
        public Criteria criteria=new Criteria();
        public List<Activity> candidates=new ArrayList<>();
        public String selectedActivityId;
        public List<Map<String,String>> recent=new ArrayList<>();
    }
    /** Persisted after every model result and every tool result. No hidden reasoning is saved. */
    public static class Checkpoint {
        public State state=new State();
        public Criteria searchGuard;
        public List<ObjectNode> messages=new ArrayList<>();
        public int modelCalls,toolCalls,tokens,decisions;
        public boolean modelInFlight;
        public String lastError;
        public Instant evidenceAt;
        public String view="CLARIFY";
        public List<String> missing=new ArrayList<>();
        public List<Activity> activities=new ArrayList<>();
        public List<Rule> rules=new ArrayList<>();
        public List<Order> orders=new ArrayList<>();
        public Map<String,JsonNode> seen=new LinkedHashMap<>();
    }
    public record Session(String id,String mode,State state,String activeRun,List<Run> turns) {}
    public record SessionItem(String id,Instant updatedAt) {}
    public record Run(String id,String sessionId,String message,String status,String mode,Reply reply,
        String errorCode,int modelCalls,int toolCalls,Integer totalTokens,Instant createdAt) {}
    public record Step(int number,String tool,String outcome,long elapsedMs) {}
    public record Claim(String id,String sessionId,long user,String message,String owner,String mode,
        Instant deadline,Checkpoint checkpoint) {}
    public record ModelAnswer(ObjectNode message,int tokens) {}
}
