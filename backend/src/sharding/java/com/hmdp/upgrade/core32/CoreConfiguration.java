package com.hmdp.upgrade.core32;

import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.hmdp.upgrade.OrderMessages;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.upgrade.Transactions;
import com.hmdp.upgrade.Faults;

@Configuration
@ConditionalOnProperty(name="upgrade.core32.enabled",havingValue="true")
public class CoreConfiguration {
    @Bean(destroyMethod="close") @DependsOn("flywayInitializer")
    CoreStore coreStore(@Value("${upgrade.core32.urls}")String urls,@Value("${upgrade.core32.username:qupiao}")String username,@Value("${upgrade.core32.password}")String password)throws Exception{return new CoreStore(urls.split(","),username,password,false);}
    @Bean(destroyMethod="close") @DependsOn("flywayInitializer") LeasedIds coreIds(JdbcTemplate source){return new LeasedIds(source);}
    @Bean CoreStock coreStock(JdbcTemplate source,Transactions transactions){return new CoreStock(source,transactions);}
    @Bean CoreRedis coreRedis(StringRedisTemplate redis){return new CoreRedis(redis);}
    @Bean CoreEngine coreEngine(CoreStore store,CoreStock stock,CoreRedis redis,LeasedIds ids,Transactions transactions,Faults faults){var engine=new CoreEngine(store,stock,redis,ids,transactions);engine.fault=faults::hit;return engine;}
    @Bean CoreJobs coreJobs(CoreEngine engine,OrderMessages messages,ObjectMapper json,@Value("${upgrade.core32.topic:qupiao-core32-orders}")String topic,@Value("${upgrade.jobs:true}")boolean jobs){return new CoreJobs(engine,messages,json,topic,jobs);}
    @Bean CoreQueries coreQueries(CoreEngine engine){return new CoreQueries(engine);}
    @Bean com.hmdp.upgrade.assistant.AssistantOrders coreAssistantOrders(CoreQueries queries) {
        return new com.hmdp.upgrade.assistant.AssistantOrders() {
            public com.hmdp.upgrade.CustomerRequests.Listing list(long user,String state) { return queries.list(user,1,state); }
            public com.hmdp.upgrade.CustomerRequests.Detail detail(long user,String id) { return queries.detail(user,id,false); }
        };
    }
    @Bean CoreWaitlists coreWaitlists(CoreEngine engine,Transactions tx,@Value("${upgrade.jobs:true}")boolean jobs){return new CoreWaitlists(engine,tx,jobs);}
    @Bean CoreMigration coreMigration(CoreEngine engine){return new CoreMigration(engine);}
    @Bean CoreRecovery coreRecovery(CoreEngine engine){return new CoreRecovery(engine);}
}
