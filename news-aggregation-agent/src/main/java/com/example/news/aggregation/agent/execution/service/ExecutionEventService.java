package com.example.news.aggregation.agent.execution.service;

import com.example.news.aggregation.agent.execution.domain.ExecutionEventLogEntity;
import com.example.news.aggregation.agent.execution.repo.ExecutionEventLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Persists execution lifecycle events.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionEventService {

    private static final int EVENT_VERSION = 1;

    private final ExecutionEventLogRepository eventLogRepository;

    public void record(String runId,
                       String stepId,
                       String eventType,
                       String fromState,
                       String toState,
                       String reasonCode,
                       String message,
                       String payloadJson) {
        ExecutionEventLogEntity entity = new ExecutionEventLogEntity();
        entity.setRunId(runId);
        entity.setStepId(stepId);
        entity.setEventType(eventType);
        entity.setEventVersion(EVENT_VERSION);
        entity.setFromState(fromState);
        entity.setToState(toState);
        entity.setReasonCode(reasonCode);
        entity.setMessage(message);
        entity.setPayloadJson(payloadJson);
        eventLogRepository.insert(entity);
        log.info("[execution-event] runId={}, stepId={}, eventType={}, from={}, to={}, reasonCode={}",
                runId, stepId, eventType, fromState, toState, reasonCode);
    }
}
