package com.hmdp.upgrade.core32;

import com.hmdp.upgrade.GlobalIds;
import java.time.Instant;
import java.util.function.LongSupplier;
import java.util.function.IntSupplier;
import java.util.concurrent.ThreadLocalRandom;

/** 41 timestamp bits, 10 worker bits, 12 sequence bits. Worker ownership is external. */
public class SnowflakeIds implements GlobalIds {
    static final long EPOCH=Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private final int worker;private final LongSupplier clock;private final IntSupplier sequenceStart;
    private long last=-1;private int sequence;
    public SnowflakeIds(int worker){this(worker,System::currentTimeMillis,()->ThreadLocalRandom.current().nextInt(32));}
    public SnowflakeIds(int worker,LongSupplier clock){this(worker,clock,()->0);}
    public SnowflakeIds(int worker,LongSupplier clock,IntSupplier sequenceStart){if(worker<0||worker>1023)throw new IllegalArgumentException("worker 0..1023 required");this.worker=worker;this.clock=clock;this.sequenceStart=sequenceStart;}
    @Override public synchronized long nextId(){
        long now=clock.getAsLong();
        if(now<EPOCH||now<last)throw new IllegalStateException("Clock moved backwards or precedes epoch");
        // Starting every millisecond at zero sends low-rate registrations to shard zero.
        if(now==last){if(sequence==4095)throw new IllegalStateException("Sequence exhausted; retry after clock advances");sequence++;}else{sequence=sequenceStart.getAsInt();if(sequence<0||sequence>31)throw new IllegalStateException("Invalid initial sequence");}
        long elapsed=now-EPOCH;if(elapsed>=(1L<<41))throw new IllegalStateException("Snowflake epoch exhausted");
        last=now;return (elapsed<<22)|((long)worker<<12)|sequence;
    }
}
