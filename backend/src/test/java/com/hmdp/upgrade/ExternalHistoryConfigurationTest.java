package com.hmdp.upgrade;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalHistoryConfigurationTest {
    @Test void disabledReplicaUsesLocalPathWithoutQueryingControlAndInvalidOptInFailsFast() {
        var source=mock(JdbcTemplate.class);
        var history=new ExternalOrderHistory(source,null,(HistoryReplica)null);
        history.validateConfiguration();assertFalse(history.readsShards());assertEquals(false,history.status().get("enabled"));verifyNoInteractions(source);
        ReflectionTestUtils.setField(history,"configured",true);
        assertThrows(IllegalStateException.class,history::validateConfiguration);
    }
    @Test void backgroundBatchHasAnExplicitBound() {
        var history=new ExternalOrderHistory(mock(JdbcTemplate.class),null,mock(HistoryReplica.class));
        ReflectionTestUtils.setField(history,"syncBatch",101);
        assertThrows(Problem.class,history::validateConfiguration);
    }
    @Test void unavailableTargetStopsTheBatchInsteadOfMultiplyingTimeouts() {
        var source=mock(JdbcTemplate.class);var replica=mock(HistoryReplica.class);
        when(source.queryForList(anyString(),eq(2))).thenReturn(java.util.List.of(
            java.util.Map.of("id","first","revision",1L),java.util.Map.of("id","second","revision",1L)));
        doThrow(new IllegalStateException("offline")).when(replica).apply(anyMap());
        var history=new ExternalOrderHistory(source,null,replica);
        assertEquals(0,history.drain(2));verify(replica,times(1)).apply(anyMap());
    }
}
