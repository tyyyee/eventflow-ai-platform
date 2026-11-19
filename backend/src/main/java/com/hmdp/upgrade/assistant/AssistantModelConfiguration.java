package com.hmdp.upgrade.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.upgrade.CustomerRequests;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class AssistantModelConfiguration {
    @Bean AssistantModel ticketAssistantModel(ObjectMapper json,
        @Value("${upgrade.assistant.mode:stub}") String mode,
        @Value("${upgrade.assistant.api-key:}") String key,
        @Value("${upgrade.assistant.base-url:https://api.deepseek.com}") String base,
        @Value("${upgrade.assistant.model:deepseek-flash}") String model) {
        return switch(mode) {
            case "stub" -> new DemoAssistantModel(json);
            case "deepseek" -> new DeepSeekAssistantModel(json,key,base,model,false);
            case "disabled" -> new AssistantModel() {
                public String mode(){return "disabled";}
                public AssistantData.ModelAnswer next(AssistantData.Checkpoint cp,String request,java.time.Instant now,long remaining){throw new com.hmdp.upgrade.Problem(503,"ASSISTANT_DISABLED");}
            };
            default -> throw new IllegalArgumentException("ASSISTANT_MODE must be stub, deepseek or disabled");
        };
    }
    @Bean @ConditionalOnProperty(name="upgrade.core32.enabled",havingValue="false",matchIfMissing=true)
    AssistantOrders singleDatabaseAssistantOrders(CustomerRequests queries) {
        return new AssistantOrders() {
            public CustomerRequests.Listing list(long user,String state){return queries.readOnlyList(user,state);}
            public CustomerRequests.Detail detail(long user,String id){return queries.readOnlyDetail(user,id);}
        };
    }
}
