package com.example.news.aggregation.agent.execution;

import com.example.news.aggregation.agent.execution.domain.ExecutionEffectLatchEntity;
import com.example.news.aggregation.agent.execution.enums.EffectStatus;
import com.example.news.aggregation.agent.execution.repo.ExecutionEffectLatchRepository;
import com.example.news.aggregation.agent.execution.service.EffectLatchService;
import com.example.news.aggregation.agent.execution.service.ExecutionEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EffectLatchServiceTest {

    @Mock
    private ExecutionEffectLatchRepository effectLatchRepository;
    @Mock
    private ExecutionEventService eventService;

    private EffectLatchService effectLatchService;

    @BeforeEach
    void setUp() {
        effectLatchService = new EffectLatchService(effectLatchRepository, eventService);
    }

    @Test
    void shouldReserveEffectLatch() {
        ExecutionEffectLatchEntity reserved = new ExecutionEffectLatchEntity();
        reserved.setStatus(EffectStatus.RESERVED.name());
        when(effectLatchRepository.insertIgnore(any(ExecutionEffectLatchEntity.class))).thenReturn(1);
        when(effectLatchRepository.findByEffectKey("run-1:step-1")).thenReturn(reserved);

        ExecutionEffectLatchEntity result = effectLatchService.reserve(
                "run-1", "step-1", "run-1:step-1", "payload-hash"
        );

        assertNotNull(result);
        assertEquals(EffectStatus.RESERVED.name(), result.getStatus());
        verify(eventService, times(1)).record(eq("run-1"), eq("step-1"),
                eq("EFFECT_RESERVED"), any(), eq("RESERVED"), any(), any(), any());
    }

    @Test
    void shouldMarkUnknownWithCas() {
        ExecutionEffectLatchEntity current = new ExecutionEffectLatchEntity();
        current.setLockVersion(0);
        current.setStatus(EffectStatus.RESERVED.name());
        when(effectLatchRepository.findByEffectKey("run-2:step-2")).thenReturn(current);
        when(effectLatchRepository.updateStatusWithCas(
                eq("run-2:step-2"), anyInt(), eq("UNKNOWN"), eq("trace-1"), any(), eq("STEP_EXCEPTION"), eq("timeout")
        )).thenReturn(1);

        boolean updated = effectLatchService.markUnknown(
                "run-2", "step-2", "run-2:step-2", "trace-1", "STEP_EXCEPTION", "timeout"
        );

        assertTrue(updated);
        verify(eventService, times(1)).record(eq("run-2"), eq("step-2"),
                eq("EFFECT_STATUS_CHANGED"), eq("RESERVED"), eq("UNKNOWN"), eq("STEP_EXCEPTION"), any(), any());
    }
}
