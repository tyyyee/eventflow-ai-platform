package com.hmdp.upgrade;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/** Successful handling means the database transaction has committed. Throw to retry. */
public interface OrderMessages {
    record Received(String topic,String broker,int queue,long offset,String key,String body,long timestamp) {}
    @FunctionalInterface interface Handler { void handle(Received message) throws Exception; }
    CompletableFuture<Void> send(String topic,String key,String body);
    CompletableFuture<Void> sendAt(String topic,String key,String body,Instant due);
    void subscribe(String topic,String group,Handler handler);
}
