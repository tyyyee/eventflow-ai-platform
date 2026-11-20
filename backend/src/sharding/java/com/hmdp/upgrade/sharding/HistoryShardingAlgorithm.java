package com.hmdp.upgrade.sharding;

import java.util.*;
import org.apache.shardingsphere.sharding.api.sharding.standard.*;

/** Fixed v1 topology: 32 global tables, first 16 in history0, last 16 in history1. */
public final class HistoryShardingAlgorithm implements StandardShardingAlgorithm<Long> {
    private boolean database;
    @Override public void init(Properties props) { database="database".equals(props.getProperty("route")); }
    @Override public String getType() { return "HISTORY_FIXED_32"; }
    @Override public String doSharding(Collection<String> names,PreciseShardingValue<Long> value) {
        long user=((Number)value.getValue()).longValue();
        if(user<=0) throw new IllegalArgumentException("A positive user_id is required");
        int shard=(int)Math.floorMod(user,32);
        String target=database?"history"+shard/16:"ux_order_read_"+String.format(Locale.ROOT,"%02d",shard);
        if(!names.contains(target)) throw new IllegalArgumentException("History topology mismatch");
        return target;
    }
    @Override public Collection<String> doSharding(Collection<String> names,RangeShardingValue<Long> value) {
        throw new IllegalArgumentException("History user ranges are not supported");
    }
}
