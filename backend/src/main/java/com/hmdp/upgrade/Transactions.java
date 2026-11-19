package com.hmdp.upgrade;

import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

@Component
public class Transactions {
    private final TransactionTemplate tx;
    public Transactions(PlatformTransactionManager manager) {
        tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(5);
    }
    public <T> T run(Supplier<T> action) {
        return execute(tx,action);
    }
    /** Batch maintenance must not extend the timeout used by trading transactions. */
    public <T> T runWithTimeout(int seconds,Supplier<T> action) {
        if(seconds<1)throw new IllegalArgumentException("Positive transaction timeout required");
        var maintenance=new TransactionTemplate(tx.getTransactionManager(),tx);
        maintenance.setTimeout(seconds);
        return execute(maintenance,action);
    }
    private <T> T execute(TransactionTemplate template,Supplier<T> action) {
        for(int attempt=0;;attempt++) {
            try { return template.execute(status -> action.get()); }
            catch(ConcurrencyFailureException retryable) {
                if(attempt==2) throw retryable;
                try { Thread.sleep(10L*(attempt+1)); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw retryable; }
            }
        }
    }
}
