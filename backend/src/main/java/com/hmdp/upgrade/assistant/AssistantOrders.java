package com.hmdp.upgrade.assistant;

import com.hmdp.upgrade.CustomerRequests;

/** Implementations must scope by the authenticated user and must not transition orders. */
public interface AssistantOrders {
    CustomerRequests.Listing list(long user,String state);
    CustomerRequests.Detail detail(long user,String requestId);
}
