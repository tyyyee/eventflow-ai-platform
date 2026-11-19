package com.hmdp.upgrade.core32;

import java.util.*;
import org.apache.shardingsphere.sharding.api.sharding.standard.*;

/** The article's 4 databases x 8 LOCAL table numbers, with one global shard value. */
@SuppressWarnings({"rawtypes","unchecked"})
public final class CoreRouteAlgorithm implements StandardShardingAlgorithm {
    private boolean database,order;
    @Override public void init(Properties properties) {
        database="database".equals(properties.getProperty("route"));
        order="order".equals(properties.getProperty("key"));
    }
    public static int shard(long user){if(user<=0)throw new IllegalArgumentException("Positive user_id required");return (int)Math.floorMod(user,32);}
    public static int logShard(String order){if(order==null||order.isBlank())throw new IllegalArgumentException("order_id required");return Math.floorMod(order.hashCode(),32);}
    @Override public String doSharding(Collection names,PreciseShardingValue value) {
        int shard=order?logShard(value.getValue().toString()):shard(((Number)value.getValue()).longValue());
        String target=database?"db"+shard/8:value.getLogicTableName()+"_"+shard%8;
        if(!names.contains(target))throw new IllegalArgumentException("Core topology mismatch: "+target);
        return target;
    }
    @Override public Collection doSharding(Collection names,RangeShardingValue value){throw new IllegalArgumentException("Equality shard key required");}
    @Override public String getType(){return "CORE_4_BY_8";}
}
