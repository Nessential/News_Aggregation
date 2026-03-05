package com.example.news.aggregation.agent.execution.service;

import com.example.news.aggregation.agent.execution.domain.ExecutionEffectLatchEntity;
import com.example.news.aggregation.agent.execution.enums.EffectStatus;
import com.example.news.aggregation.agent.execution.repo.ExecutionEffectLatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Idempotency latch service for side-effect steps.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EffectLatchService {

    private final ExecutionEffectLatchRepository effectLatchRepository;
    private final ExecutionEventService eventService;

    public String buildEffectKey(String runId, String stepId) {
        return runId + ":" + stepId;
    }

    /**
     * A side-effect step can execute only after reserve succeeds.
     */
    public ExecutionEffectLatchEntity reserve(String runId,
                                              String stepId,
                                              String effectKey,
                                              String requestPayloadHash) {
        ExecutionEffectLatchEntity entity = new ExecutionEffectLatchEntity();
        entity.setEffectKey(effectKey);
        entity.setRunId(runId);
        entity.setStepId(stepId);
        entity.setStatus(EffectStatus.RESERVED.name());
        entity.setRequestPayloadHash(requestPayloadHash);
        entity.setDeleted(0);
        entity.setLockVersion(0);

        int inserted = effectLatchRepository.insertIgnore(entity);
        ExecutionEffectLatchEntity current = effectLatchRepository.findByEffectKey(effectKey);
        if (inserted > 0) {
            eventService.record(runId,
                    stepId,
                    "EFFECT_RESERVED",
                    null,
                    EffectStatus.RESERVED.name(),
                    null,
                    "side effect latch reserved",
                    null);
        }
        return current;
    }

    public boolean isApplied(String effectKey) {
        ExecutionEffectLatchEntity current = effectLatchRepository.findByEffectKey(effectKey);
        return current != null && EffectStatus.APPLIED.name().equals(current.getStatus());
    }

    public boolean markApplied(String runId,
                               String stepId,
                               String effectKey,
                               String providerTrace,
                               String responseDigest) {
        return updateStatus(runId, stepId, effectKey, EffectStatus.APPLIED, providerTrace, responseDigest, null, null);
    }

    public boolean markUnknown(String runId,
                               String stepId,
                               String effectKey,
                               String providerTrace,
                               String errorCode,
                               String errorMessage) {
        return updateStatus(runId, stepId, effectKey, EffectStatus.UNKNOWN, providerTrace, null, errorCode, errorMessage);
    }

    public boolean markFailed(String runId,
                              String stepId,
                              String effectKey,
                              String errorCode,
                              String errorMessage) {
        return updateStatus(runId, stepId, effectKey, EffectStatus.FAILED, null, null, errorCode, errorMessage);
    }

    public ExecutionEffectLatchEntity findByEffectKey(String effectKey) {
        return effectLatchRepository.findByEffectKey(effectKey);
    }

    private boolean updateStatus(String runId,
                                 String stepId,
                                 String effectKey,
                                 EffectStatus status,
                                 String providerTrace,
                                 String responseDigest,
                                 String errorCode,
                                 String errorMessage) {
        for (int i = 0; i < 3; i++) {
            ExecutionEffectLatchEntity current = effectLatchRepository.findByEffectKey(effectKey);
            if (current == null) {
                return false;
            }
            String persistedTrace = current.getProviderTrace();
            String effectiveTrace = (providerTrace == null || providerTrace.isBlank()) ? persistedTrace : providerTrace;
            int rows = effectLatchRepository.updateStatusWithCas(
                    effectKey,
                    current.getLockVersion() == null ? 0 : current.getLockVersion(),
                    status.name(),
                    effectiveTrace,
                    responseDigest,
                    errorCode,
                    errorMessage
            );
            if (rows > 0) {
                eventService.record(runId,
                        stepId,
                        "EFFECT_STATUS_CHANGED",
                        current.getStatus(),
                        status.name(),
                        errorCode,
                        "side effect latch status changed",
                        null);
                return true;
            }
        }
        return false;
    }
}
