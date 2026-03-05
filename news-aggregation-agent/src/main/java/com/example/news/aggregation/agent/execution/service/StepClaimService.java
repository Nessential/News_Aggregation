package com.example.news.aggregation.agent.execution.service;

import com.example.news.aggregation.agent.execution.config.ExecutionPersistenceProperties;
import com.example.news.aggregation.agent.execution.domain.ExecutionStepRunEntity;
import com.example.news.aggregation.agent.execution.enums.StepStatus;
import com.example.news.aggregation.agent.execution.repo.ExecutionStepRunRepository;
import com.example.news.aggregation.agent.workflow.WorkflowStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Manages step-level execution state (claim/retry/recovery/events).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StepClaimService {

    private final ExecutionStepRunRepository stepRunRepository;
    private final ExecutionPersistenceProperties properties;
    private final ExecutionEventService eventService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void prepareStepRuns(String runId,
                                List<WorkflowStep> steps,
                                int defaultMaxRetries,
                                int defaultMaxRecoveryAttempts) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        for (WorkflowStep step : steps) {
            if (step == null || step.getStepId() == null || step.getStepId().isBlank()) {
                continue;
            }
            prepareStepRun(runId, step, defaultMaxRetries, defaultMaxRecoveryAttempts);
        }
    }

    public void prepareStepRun(String runId,
                               WorkflowStep step,
                               int defaultMaxRetries,
                               int defaultMaxRecoveryAttempts) {
        ExecutionStepRunEntity entity = new ExecutionStepRunEntity();
        entity.setRunId(runId);
        entity.setStepId(step.getStepId());
        entity.setCapabilityName(step.getCapabilityName());
        entity.setActiveCapabilityName(step.getCapabilityName());
        entity.setStatus(StepStatus.PENDING.name());
        entity.setAttempt(0);
        entity.setRecoveryAttempt(0);
        entity.setMaxRetries(resolveMaxRetries(step, defaultMaxRetries));
        entity.setMaxRecoveryAttempts(defaultMaxRecoveryAttempts);
        entity.setDependsOnJson(toJson(step.getDependsOn()));
        entity.setInputJson(toJson(step.getParameters()));
        entity.setOutputJson(null);
        entity.setSideEffect(step.getSideEffect());
        entity.setFallbackToolsJson(toJson(resolveFallbackTools(step)));
        entity.setReplanAllowed(step.getFailurePolicy() != null ? step.getFailurePolicy().isReplanAllowed() : null);
        entity.setNeedUserInputOnFailure(step.getFailurePolicy() != null
                ? step.getFailurePolicy().isNeedUserInputOnFailure()
                : null);
        entity.setResumeMode(step.getFailurePolicy() != null && step.getFailurePolicy().getResumeMode() != null
                ? step.getFailurePolicy().getResumeMode().name()
                : null);
        entity.setDeleted(0);
        entity.setLockVersion(0);

        int rows = stepRunRepository.insertIgnore(entity);
        if (rows > 0) {
            eventService.record(runId,
                    step.getStepId(),
                    "STEP_CREATED",
                    null,
                    StepStatus.PENDING.name(),
                    null,
                    "create step run record",
                    null);
        }
    }

    /**
     * Claim execution ownership with CAS: supports PENDING->RUNNING and expired RUNNING lease takeover.
     */
    public boolean claimStepCas(String runId, String stepId) {
        ExecutionStepRunEntity current = stepRunRepository.findByRunIdAndStepId(runId, stepId);
        if (current == null) {
            return false;
        }

        Integer expected = defaultVersion(current.getLockVersion());
        Date leaseUntil = new Date(System.currentTimeMillis() + properties.getPersistence().getStepLeaseSeconds() * 1000L);
        String workerId = properties.getPersistence().getWorkerId();

        if (StepStatus.PENDING.name().equals(current.getStatus())) {
            int rows = stepRunRepository.claimPendingWithCas(runId, stepId, expected, workerId, leaseUntil);
            if (rows > 0) {
                eventService.record(runId,
                        stepId,
                        "STEP_CLAIMED",
                        StepStatus.PENDING.name(),
                        StepStatus.RUNNING.name(),
                        null,
                        "claim pending step",
                        null);
                return true;
            }
            return false;
        }

        if (!properties.getPersistence().isTakeoverEnabled()) {
            return false;
        }

        if (StepStatus.RUNNING.name().equals(current.getStatus())
                && current.getLeaseUntil() != null
                && current.getLeaseUntil().before(new Date())) {
            int rows = stepRunRepository.takeoverExpiredRunningWithCas(runId, stepId, expected, workerId, leaseUntil);
            if (rows > 0) {
                eventService.record(runId,
                        stepId,
                        "STEP_TAKEN_OVER",
                        StepStatus.RUNNING.name(),
                        StepStatus.RUNNING.name(),
                        "lease_expired_takeover",
                        "take over expired running step",
                        null);
                return true;
            }
        }
        return false;
    }

    public boolean heartbeat(String runId, String stepId) {
        ExecutionStepRunEntity current = stepRunRepository.findByRunIdAndStepId(runId, stepId);
        if (current == null || !StepStatus.RUNNING.name().equals(current.getStatus())) {
            return false;
        }
        Date leaseUntil = new Date(System.currentTimeMillis() + properties.getPersistence().getStepLeaseSeconds() * 1000L);
        int rows = stepRunRepository.heartbeatWithCas(
                runId,
                stepId,
                defaultVersion(current.getLockVersion()),
                properties.getPersistence().getWorkerId(),
                leaseUntil
        );
        return rows > 0;
    }

    public boolean markSucceeded(String runId, String stepId, String outputJson) {
        ExecutionStepRunEntity current = stepRunRepository.findByRunIdAndStepId(runId, stepId);
        if (current == null) {
            return false;
        }
        int rows = stepRunRepository.markSucceededWithCas(
                runId,
                stepId,
                defaultVersion(current.getLockVersion()),
                outputJson,
                new Date()
        );
        if (rows > 0) {
            eventService.record(runId,
                    stepId,
                    "STEP_SUCCEEDED",
                    current.getStatus(),
                    StepStatus.SUCCEEDED.name(),
                    null,
                    "step execution succeeded",
                    null);
            return true;
        }
        return false;
    }

    public boolean markFailed(String runId, String stepId, String reasonCode, String errorCode, String errorMessage) {
        return markTerminal(runId, stepId, StepStatus.FAILED.name(), reasonCode, errorCode, errorMessage);
    }

    public boolean markWaiting(String runId, String stepId, String reasonCode, String errorCode, String errorMessage) {
        return markTerminal(runId, stepId, StepStatus.WAITING.name(), reasonCode, errorCode, errorMessage);
    }

    public boolean markTerminal(String runId,
                                String stepId,
                                String toStatus,
                                String reasonCode,
                                String errorCode,
                                String errorMessage) {
        ExecutionStepRunEntity current = stepRunRepository.findByRunIdAndStepId(runId, stepId);
        if (current == null) {
            return false;
        }
        int rows = stepRunRepository.markTerminalWithCas(
                runId,
                stepId,
                defaultVersion(current.getLockVersion()),
                toStatus,
                reasonCode,
                errorCode,
                errorMessage,
                new Date()
        );
        if (rows > 0) {
            eventService.record(runId,
                    stepId,
                    "STEP_TERMINAL",
                    current.getStatus(),
                    toStatus,
                    reasonCode,
                    "step enters terminal status",
                    null);
            return true;
        }
        return false;
    }

    /**
     * Move a RUNNING step back to PENDING and increment the retry attempt counter.
     */
    public boolean markRetryPending(String runId,
                                    String stepId,
                                    String reasonCode,
                                    String errorCode,
                                    String errorMessage) {
        ExecutionStepRunEntity current = stepRunRepository.findByRunIdAndStepId(runId, stepId);
        if (current == null) {
            return false;
        }
        int attempt = current.getAttempt() == null ? 0 : current.getAttempt();
        int maxRetries = current.getMaxRetries() == null ? 0 : current.getMaxRetries();
        if (attempt >= maxRetries) {
            return false;
        }
        int rows = stepRunRepository.markRetryPendingWithCas(
                runId,
                stepId,
                defaultVersion(current.getLockVersion()),
                reasonCode,
                errorCode,
                errorMessage
        );
        if (rows > 0) {
            eventService.record(runId,
                    stepId,
                    "STEP_RETRY_SCHEDULED",
                    current.getStatus(),
                    StepStatus.PENDING.name(),
                    reasonCode,
                    "step scheduled for retry",
                    null);
            return true;
        }
        return false;
    }

    /**
     * Schedule fallback retry: switch activeCapabilityName to the next available tool and set step to PENDING.
     */
    public String scheduleFallbackRetry(String runId,
                                        String stepId,
                                        String reasonCode,
                                        String errorCode,
                                        String errorMessage) {
        ExecutionStepRunEntity current = stepRunRepository.findByRunIdAndStepId(runId, stepId);
        if (current == null) {
            return null;
        }
        int attempt = current.getAttempt() == null ? 0 : current.getAttempt();
        int maxRetries = current.getMaxRetries() == null ? 0 : current.getMaxRetries();
        if (attempt >= maxRetries) {
            return null;
        }

        String nextCapability = resolveNextFallbackCapability(current);
        if (nextCapability == null || nextCapability.isBlank()) {
            return null;
        }

        int rows = stepRunRepository.markRetryPendingSwitchCapabilityWithCas(
                runId,
                stepId,
                defaultVersion(current.getLockVersion()),
                nextCapability,
                reasonCode,
                errorCode,
                errorMessage
        );
        if (rows > 0) {
            eventService.record(
                    runId,
                    stepId,
                    "STEP_FALLBACK_SCHEDULED",
                    current.getStatus(),
                    StepStatus.PENDING.name(),
                    reasonCode,
                    "step switched to fallback tool and scheduled for retry",
                    "{\"fromTool\":\"" + nullToEmpty(resolveActiveCapability(current))
                            + "\",\"toTool\":\"" + nextCapability + "\"}"
            );
            return nextCapability;
        }
        return null;
    }

    public boolean updateOutputSnapshot(String runId, String stepId, String outputJson) {
        ExecutionStepRunEntity current = stepRunRepository.findByRunIdAndStepId(runId, stepId);
        if (current == null) {
            return false;
        }
        return stepRunRepository.updateOutputWithCas(
                runId,
                stepId,
                defaultVersion(current.getLockVersion()),
                outputJson
        ) > 0;
    }

    public List<ExecutionStepRunEntity> listExpiredRunning(int limit) {
        return stepRunRepository.listExpiredRunning(limit);
    }

    public List<ExecutionStepRunEntity> listByRunId(String runId) {
        return stepRunRepository.findByRunId(runId);
    }

    /**
     * Recover an expired RUNNING step: go back to PENDING and increment recovery_attempt, or WAITING when exhausted.
     */
    public boolean recoverExpiredStep(ExecutionStepRunEntity expiredStep) {
        if (expiredStep == null) {
            return false;
        }
        int currentRecovery = expiredStep.getRecoveryAttempt() == null ? 0 : expiredStep.getRecoveryAttempt();
        int maxRecovery = expiredStep.getMaxRecoveryAttempts() == null
                ? properties.getRecovery().getMaxRecoveryAttempts()
                : expiredStep.getMaxRecoveryAttempts();

        if (currentRecovery + 1 > maxRecovery) {
            return markWaiting(
                    expiredStep.getRunId(),
                    expiredStep.getStepId(),
                    "recovery_exhausted",
                    "RECOVERY_EXHAUSTED",
                    "recovery attempts exhausted"
            );
        }

        int rows = stepRunRepository.recoverToPendingWithCas(
                expiredStep.getRunId(),
                expiredStep.getStepId(),
                defaultVersion(expiredStep.getLockVersion()),
                currentRecovery + 1,
                "recovered_from_expired_running",
                "LEASE_EXPIRED",
                "expired RUNNING step moved back to PENDING"
        );
        if (rows > 0) {
            eventService.record(
                    expiredStep.getRunId(),
                    expiredStep.getStepId(),
                    "STEP_RECOVERED",
                    StepStatus.RUNNING.name(),
                    StepStatus.PENDING.name(),
                    "lease_expired",
                    "recovery worker reset step to PENDING",
                    null
            );
            return true;
        }
        return false;
    }

    public ExecutionStepRunEntity findStepRun(String runId, String stepId) {
        return stepRunRepository.findByRunIdAndStepId(runId, stepId);
    }

    public int getAttempt(String runId, String stepId) {
        ExecutionStepRunEntity entity = findStepRun(runId, stepId);
        if (entity == null || entity.getAttempt() == null) {
            return 0;
        }
        return entity.getAttempt();
    }

    public int getMaxRetries(String runId, String stepId, int defaultMaxRetries) {
        ExecutionStepRunEntity entity = findStepRun(runId, stepId);
        if (entity == null || entity.getMaxRetries() == null) {
            return defaultMaxRetries;
        }
        return entity.getMaxRetries();
    }

    public Map<String, Object> buildStepRuntimeView(String runId, String stepId) {
        ExecutionStepRunEntity entity = findStepRun(runId, stepId);
        if (entity == null) {
            return Map.of();
        }
        return Map.of(
                "attempt", entity.getAttempt() == null ? 0 : entity.getAttempt(),
                "maxRetries", entity.getMaxRetries() == null ? 0 : entity.getMaxRetries(),
                "status", Objects.toString(entity.getStatus(), ""),
                "recoveryAttempt", entity.getRecoveryAttempt() == null ? 0 : entity.getRecoveryAttempt(),
                "maxRecoveryAttempts", entity.getMaxRecoveryAttempts() == null ? 0 : entity.getMaxRecoveryAttempts(),
                "capabilityName", nullToEmpty(entity.getCapabilityName()),
                "activeCapabilityName", nullToEmpty(entity.getActiveCapabilityName())
        );
    }

    public String getEffectiveCapability(String runId, String stepId) {
        ExecutionStepRunEntity entity = findStepRun(runId, stepId);
        if (entity == null) {
            return null;
        }
        return resolveActiveCapability(entity);
    }

    private int resolveMaxRetries(WorkflowStep step, int defaultMaxRetries) {
        if (step != null
                && step.getRetryPolicy() != null
                && step.getRetryPolicy().getMaxRetries() != null) {
            return Math.max(0, step.getRetryPolicy().getMaxRetries());
        }
        return Math.max(0, defaultMaxRetries);
    }

    private List<String> resolveFallbackTools(WorkflowStep step) {
        if (step == null || step.getFailurePolicy() == null || step.getFailurePolicy().getFallbackTools() == null) {
            return List.of();
        }
        return step.getFailurePolicy().getFallbackTools()
                .stream()
                .filter(item -> item != null && !item.isBlank())
                .toList();
    }

    private String resolveNextFallbackCapability(ExecutionStepRunEntity entity) {
        List<String> ordered = new ArrayList<>();
        if (entity.getCapabilityName() != null && !entity.getCapabilityName().isBlank()) {
            ordered.add(entity.getCapabilityName());
        }
        for (String fallback : readFallbackTools(entity.getFallbackToolsJson())) {
            if (fallback != null && !fallback.isBlank() && !ordered.contains(fallback)) {
                ordered.add(fallback);
            }
        }
        if (ordered.size() <= 1) {
            return null;
        }
        String current = resolveActiveCapability(entity);
        int currentIndex = ordered.indexOf(current);
        int nextIndex = currentIndex < 0 ? 1 : currentIndex + 1;
        if (nextIndex >= ordered.size()) {
            return null;
        }
        return ordered.get(nextIndex);
    }

    @SuppressWarnings("unchecked")
    private List<String> readFallbackTools(String fallbackToolsJson) {
        if (fallbackToolsJson == null || fallbackToolsJson.isBlank()) {
            return List.of();
        }
        try {
            List<Object> raw = objectMapper.readValue(fallbackToolsJson, List.class);
            List<String> normalized = new ArrayList<>();
            for (Object item : raw) {
                if (item == null) {
                    continue;
                }
                String value = String.valueOf(item).trim();
                if (!value.isBlank()) {
                    normalized.add(value);
                }
            }
            return normalized;
        } catch (Exception e) {
            log.warn("[step-claim] fallbackToolsJson parse failed|json={} |error={}", fallbackToolsJson, e.getMessage());
            return List.of();
        }
    }

    private String resolveActiveCapability(ExecutionStepRunEntity entity) {
        if (entity == null) {
            return null;
        }
        if (entity.getActiveCapabilityName() != null && !entity.getActiveCapabilityName().isBlank()) {
            return entity.getActiveCapabilityName();
        }
        return entity.getCapabilityName();
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[step-claim] JSON serialize failed|valueClass={} |error={}", value.getClass().getName(), e.getMessage());
            return null;
        }
    }

    private Integer defaultVersion(Integer version) {
        return version == null ? 0 : version;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
