package com.hmdp.upgrade.sharding;

import com.hmdp.upgrade.HistoryReplica;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@ConditionalOnProperty(name="upgrade.history.replica-enabled",havingValue="true")
class HistoryReplicaConfiguration {
    @Bean(destroyMethod="close") HistoryReplica historyReplica(
        @Value("${upgrade.history.urls}") String urls,
        @Value("${upgrade.history.username}") String username,
        @Value("${upgrade.history.password}") String password) throws Exception {
        return new ShardedHistoryReplica(urls.split(","),username,password);
    }
}
