package com.hmdp.upgrade.core32;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class Core32IdsTest {
    @Test void sequenceWorkerAndClockBoundaries(){
        var clock=new AtomicLong(SnowflakeIds.EPOCH+1000);var first=new SnowflakeIds(1,clock::get);var second=new SnowflakeIds(2,clock::get);var values=new HashSet<Long>();
        for(int i=0;i<4096;i++){assertTrue(values.add(first.nextId()));assertTrue(values.add(second.nextId()));}
        assertThrows(IllegalStateException.class,first::nextId);clock.incrementAndGet();assertTrue(values.add(first.nextId()));
        clock.decrementAndGet();assertThrows(IllegalStateException.class,first::nextId);assertThrows(IllegalArgumentException.class,()->new SnowflakeIds(1024));
    }
    @Test void articleExampleUsesFourDatabasesAndLocalTableNumbers(){
        int shard=CoreRouteAlgorithm.shard(10086);assertEquals(6,shard);assertEquals(0,shard/8);assertEquals(6,shard%8);
        assertEquals(3,CoreRouteAlgorithm.shard(31)/8);assertEquals(7,CoreRouteAlgorithm.shard(31)%8);
        assertThrows(IllegalArgumentException.class,()->CoreRouteAlgorithm.shard(0));
    }
    @Test void lowRateIdsCanReachEveryModuloShardWithoutLosingMonotonicity(){
        var clock=new AtomicLong(SnowflakeIds.EPOCH+1000);var start=new java.util.concurrent.atomic.AtomicInteger();
        var ids=new SnowflakeIds(0,clock::get,()->start.getAndIncrement()%32);var shards=new HashSet<Integer>();long previous=0;
        for(int i=0;i<64;i++){long id=ids.nextId();assertTrue(id>previous);previous=id;shards.add(CoreRouteAlgorithm.shard(id));clock.incrementAndGet();}
        assertEquals(32,shards.size());
    }
}
