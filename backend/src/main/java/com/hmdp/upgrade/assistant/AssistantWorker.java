package com.hmdp.upgrade.assistant;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;

@Component
public class AssistantWorker {
    private final AssistantService service;private final AssistantRuntime runtime;private final boolean enabled;
    private final Semaphore slots=new Semaphore(2);
    private final ExecutorService workers=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"ticket-assistant");t.setDaemon(true);return t;});
    public AssistantWorker(AssistantService service,AssistantRuntime runtime,@Value("${upgrade.assistant.jobs:true}")boolean enabled){this.service=service;this.runtime=runtime;this.enabled=enabled;}
    @Scheduled(fixedDelay=500)
    public void tick(){
        if(!enabled||runtime.mode().equals("disabled"))return;
        if(slots.tryAcquire())try {workers.submit(()->{try{service.workOne();}catch(Exception failed){org.slf4j.LoggerFactory.getLogger(AssistantWorker.class).warn("Assistant worker iteration failed; durable work remains recoverable");}finally{slots.release();}});}
        catch(RejectedExecutionException stopping){slots.release();}
    }
    @PreDestroy public void stop(){workers.shutdownNow();}
}
