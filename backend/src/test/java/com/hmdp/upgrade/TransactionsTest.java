package com.hmdp.upgrade;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransactionsTest {
    @Test void maintenanceTimeoutDoesNotChangeTradingTimeout() {
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        var transactions=new Transactions(manager);
        assertEquals("before",transactions.run(()->"before"));
        assertEquals("maintenance",transactions.runWithTimeout(120,()->"maintenance"));
        assertEquals("after",transactions.run(()->"after"));
        var definitions=ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager,times(3)).getTransaction(definitions.capture());
        assertEquals(java.util.List.of(5,120,5),definitions.getAllValues().stream().map(TransactionDefinition::getTimeout).toList());
        assertThrows(IllegalArgumentException.class,()->transactions.runWithTimeout(0,()->null));
    }
}
