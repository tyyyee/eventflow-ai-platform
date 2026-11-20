package com.hmdp.upgrade.assistant;

import java.time.Instant;
import static com.hmdp.upgrade.assistant.AssistantData.*;

public interface AssistantModel {
    String mode();
    ModelAnswer next(Checkpoint checkpoint,String request,Instant now,long remainingMillis);
}
