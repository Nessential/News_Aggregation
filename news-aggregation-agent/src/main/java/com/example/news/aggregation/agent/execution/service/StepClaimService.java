package com.example.news.aggregation.agent.execution.service;

import com.example.news.aggregation.agent.execution.config.ExecutionPersistenceProperties;
import com.example.news.aggregation.agent.execution.domain.ExecutionStepRunEntity;
import com.example.news.aggregation.agent.execution.enums.StepStatus;
import com.example.news.aggregation.agent.execution.repo.ExecutionStepRunRepository;
import com.example.news.aggregation.agent.workflow.WorkflowStep;
import com.example.news.aggregation.agent.workflow.selector.ToolFailureCategory;
import com.example.news.aggregation.agent.workflow.selector.ToolSelectionResult;
import com.example.news.aggregation.agent.workflow.selector.ToolSelectorV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 管理 step 级执行状态（抢占、重试、回填、事件记录）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StepClaimService {

    private final ExecutionStepRunRepository stepRunRepository;
    private final ExecutionPersistenceProperties properties;
    private final ExecutionEventService eventService;
    @Autowired(required = false)
    private ToolSelectorV1 toolSelector;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 回退调度结果：
     * selectedTool 为空表示未能安排回退，failureReasonCode 用于排障透出。
     */
    public record FallbackScheduleResult(String selectedTool, String failureReasonCode) {
        public boolean hasSelectedTool() {
            return selectedTool != null && !selectedTool.isBlank();
        }
    }

    public void prepareStepRuns(String runId,
                                List<WorkflowStep> steps,
                                int defaultMaxRetries,
                                int defaultMaxRecoveryAttempts) {
        prepareStepRuns(runId, steps, defaultMaxRetries, defaultMaxRecoveryAttempts, 1);
    }

    public void prepareStepRuns(String runId,
                                List<WorkflowStep> steps,
                                int defaultMaxRetries,
                                int defaultMaxRecoveryAttempts,
                                int planVersion) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        for (WorkflowStep step : steps) {
            if (step == null || step.getStepId() == null || step.getStepId().isBlank()) {
                continue;
            }
            prepareStepRun(runId, step, defaultMaxRetries, defaultMaxRecoveryAttempts, planVersion);
        }
    }

    public void prepareStepRun(String runId,
                               WorkflowStep step,
                               int defaultMaxRetries,
                               int defaultMaxRecoveryAttempts) {
        prepareStepRun(runId, step, defaultMaxRetries, defaultMaxRecoveryAttempts, 1);
    }

    public void prepareStepRun(String runId,
                               WorkflowStep step,
                               int defaultMaxRetries,
                               int defaultMaxRecoveryAttempts,
                               int planVersion) {
        ExecutionStepRunEntity entity = new ExecutionStepRunEntity();
        entity.setRunId(runId);
        entity.setStepId(step.getStepId());
        entity.setPlanVersion(Math.max(1, planVersion));
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
        entity.setSelectedTool(step.getCapabilityName());
        entity.setSelectionReasonCode("initial_primary");
        entity.setCircuitStateSnapshot(null);
        entity.setFallbackCandidatesJson(toJson(resolveFallbackTools(step)));
        entity.setReplanCountStep(0);
        entity.setLastReplanReasonCode(null);
        entity.setChangeProofSnapshot(null);
        entity.setEvidenceSnapshot(null);
        entity.setReplanDecisionAction(null);
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
     * 基于选择器结果安排回退重试，并将 step 回到 PENDING。
     */
    public String scheduleFallbackRetry(String runId,
                                        String stepId,
                                        String reasonCode,
                                        String errorCode,
                                        String errorMessage) {
        return scheduleFallbackRetryDetailed(runId, stepId, reasonCode, errorCode, errorMessage).selectedTool();
    }

    public FallbackScheduleResult scheduleFallbackRetryDetailed(String runId,
                                                                String stepId,
                                                                String reasonCode,
                                                                String errorCode,
                                                                String errorMessage) {
        ExecutionStepRunEntity current = stepRunRepository.findByRunIdAndStepId(runId, stepId);
        if (current == null) {
            log.warn("[step-claim] 回退调度失败：step_run 不存在|runId={} |stepId={}", runId, stepId);
            return new FallbackScheduleResult(null, "fallback_unavailable");
        }
        int attempt = current.getAttempt() == null ? 0 : current.getAttempt();
        int maxRetries = current.getMaxRetries() == null ? 0 : current.getMaxRetries();
        if (attempt >= maxRetries) {
            log.warn("[step-claim] 回退调度失败：重试次数已耗尽|runId={} |stepId={} |attempt={}/{}",
                    runId, stepId, attempt, maxRetries);
            return new FallbackScheduleResult(null, "fallback_unavailable");
        }

        String nextCapability = resolveNextFallbackCapability(current);
        String selectionReasonCode = "fallback_tool";
        String circuitSnapshot = null;
        String fallbackCandidates = null;
        if (toolSelector != null) {
            ToolSelectionResult result = toolSelector.select(
                    nonBlank(current.getCapabilityName(), current.getActiveCapabilityName()),
                    nonBlank(current.getCapabilityName(), current.getActiveCapabilityName()),
                    readFallbackTools(current.getFallbackToolsJson()),
                    current.getSelectedTool(),
                    true,
                    true
            );
            if (result == null || !result.hasSelectedTool()) {
                String failureReasonCode = deriveFallbackUnavailableReason(result == null ? null : result.getReasonCode());
                updateSelectionSnapshotWithRetry(
                        runId,
                        stepId,
                        nonBlank(current.getSelectedTool(), resolveActiveCapability(current)),
                        failureReasonCode,
                        result == null ? null : toJson(result.getCircuitStateByTool()),
                        result == null ? null : toJson(result.getCandidates())
                );
                log.warn("[step-claim] 回退调度失败：选择器未选出可用工具|runId={} |stepId={} |reasonCode={}",
                        runId, stepId, failureReasonCode);
                return new FallbackScheduleResult(null, failureReasonCode);
            }
            nextCapability = result.getSelectedTool();
            selectionReasonCode = nonBlank(result.getReasonCode(), "fallback_tool");
            circuitSnapshot = toJson(result.getCircuitStateByTool());
            fallbackCandidates = toJson(result.getCandidates());
        }

        if (nextCapability == null || nextCapability.isBlank()) {
            log.warn("[step-claim] 回退调度失败：未解析到候选工具|runId={} |stepId={}", runId, stepId);
            return new FallbackScheduleResult(null, deriveFallbackUnavailableReason(selectionReasonCode));
        }

        int rows = stepRunRepository.markRetryPendingSwitchCapabilityWithCas(
                runId,
                stepId,
                defaultVersion(current.getLockVersion()),
                nextCapability,
                selectionReasonCode,
                errorCode,
                errorMessage
        );
        if (rows > 0) {
            updateSelectionSnapshotWithRetry(
                    runId,
                    stepId,
                    nextCapability,
                    selectionReasonCode,
                    circuitSnapshot,
                    fallbackCandidates
            );
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
            log.info("[step-claim] 回退调度成功|runId={} |stepId={} |toTool={} |selectionReason={}",
                    runId, stepId, nextCapability, selectionReasonCode);
            return new FallbackScheduleResult(nextCapability, null);
        }
        log.warn("[step-claim] 回退调度失败：CAS 冲突耗尽|runId={} |stepId={} |reasonCode={}",
                runId, stepId, selectionReasonCode);
        return new FallbackScheduleResult(null, deriveFallbackUnavailableReason(selectionReasonCode));
    }

    /**
     * 执行前选择工具，并通过 CAS 持久化选择快照。
     */
    public ToolSelectionResult selectToolForExecution(String runId,
                                                      WorkflowStep step,
                                                      boolean forceReselect,
                                                      boolean fallbackAction) {
        if (step == null || runId == null || runId.isBlank()) {
            log.warn("[step-claim] 选择工具失败：入参不完整|runId={} |stepId={}",
                    runId, step == null ? null : step.getStepId());
            return null;
        }
        ExecutionStepRunEntity current = stepRunRepository.findByRunIdAndStepId(runId, step.getStepId());
        if (current == null) {
            log.warn("[step-claim] 选择工具失败：step_run 不存在|runId={} |stepId={}", runId, step.getStepId());
            return null;
        }
        String primaryTool = nonBlank(current.getCapabilityName(), step.getCapabilityName());
        List<String> fallbackTools = mergeFallbackTools(step, current);
        String selected = nonBlank(current.getSelectedTool(), resolveActiveCapability(current));
        ToolSelectionResult result;
        if (toolSelector == null) {
            log.warn("[step-claim] 选择器未启用，使用已选工具或主工具兜底|runId={} |stepId={}", runId, step.getStepId());
            result = ToolSelectionResult.builder()
                    .selectedTool(nonBlank(selected, primaryTool))
                    .reasonCode("selector_unavailable")
                    .candidates(buildOrderedCandidates(primaryTool, fallbackTools))
                    .circuitStateByTool(Map.of())
                    .healthSnapshotByTool(Map.of())
                    .build();
        } else {
            result = toolSelector.select(
                    nonBlank(current.getCapabilityName(), step.getCapabilityName()),
                    primaryTool,
                    fallbackTools,
                    selected,
                    forceReselect,
                    fallbackAction
            );
        }
        if (result == null || !result.hasSelectedTool()) {
            log.warn("[step-claim] 未选出可执行工具|runId={} |stepId={} |reasonCode={}",
                    runId, step.getStepId(), result == null ? null : result.getReasonCode());
            return result;
        }
        updateActiveSelectionSnapshotWithRetry(
                runId,
                step.getStepId(),
                result.getSelectedTool(),
                result.getSelectedTool(),
                nonBlank(result.getReasonCode(), "tool_selected"),
                toJson(result.getCircuitStateByTool()),
                toJson(result.getCandidates())
        );
        log.info("[step-claim] 工具选择完成|runId={} |stepId={} |selectedTool={} |reasonCode={}",
                runId, step.getStepId(), result.getSelectedTool(), result.getReasonCode());
        return result;
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

    /**
     * 记录一次 Replan 尝试（step 级计数 + 快照）。
     * 说明：
     * 1. 仅用于“进入 REPLAN 分支”时的审计回填；
     * 2. 使用 CAS + 有限重试，避免并发写入导致计数丢失；
     * 3. 该计数不会替代 run 级预算，run 级预算由 ExecutionRunService 单独管理。
     */
    public boolean recordReplanAttempt(String runId,
                                       String stepId,
                                       String reasonCode,
                                       String changeProofSnapshot,
                                       String evidenceSnapshot,
                                       String replanDecisionAction) {
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            ExecutionStepRunEntity current = stepRunRepository.findByRunIdAndStepId(runId, stepId);
            if (current == null) {
                return false;
            }
            int rows = stepRunRepository.recordReplanAttemptWithCas(
                    runId,
                    stepId,
                    defaultVersion(current.getLockVersion()),
                    nonBlank(reasonCode, "replan_required"),
                    changeProofSnapshot,
                    evidenceSnapshot,
                    nonBlank(replanDecisionAction, "REPLAN")
            );
            if (rows > 0) {
                log.info("[step-claim] Replan 回填成功|runId={} |stepId={} |reasonCode={} |decisionAction={}",
                        runId, stepId, reasonCode, replanDecisionAction);
                return true;
            }
        }
        log.warn("[step-claim] Replan 回填失败：CAS 重试耗尽|runId={} |stepId={} |reasonCode={} |decisionAction={}",
                runId, stepId, reasonCode, replanDecisionAction);
        return false;
    }

    /**
     * 通过 CAS 持久化工具选择快照，保障重放与排障可追溯。
     */
    public boolean updateSelectionSnapshot(String runId,
                                           String stepId,
                                           String selectedTool,
                                           String selectionReasonCode,
                                           String circuitStateSnapshot,
                                           String fallbackCandidatesJson) {
        return updateSelectionSnapshotWithRetry(
                runId,
                stepId,
                selectedTool,
                selectionReasonCode,
                circuitStateSnapshot,
                fallbackCandidatesJson
        );
    }

    public void recordToolSuccess(String selectedTool, String capability, long latencyMs) {
        if (toolSelector == null || selectedTool == null || selectedTool.isBlank()) {
            return;
        }
        toolSelector.recordSuccess(selectedTool, capability, latencyMs);
    }

    public void recordToolFailure(String selectedTool,
                                  String capability,
                                  ToolFailureCategory failureCategory,
                                  long latencyMs,
                                  String reasonCode) {
        if (toolSelector == null || selectedTool == null || selectedTool.isBlank()) {
            return;
        }
        toolSelector.recordFailure(selectedTool, capability, failureCategory, latencyMs, reasonCode);
    }

    public List<ExecutionStepRunEntity> listExpiredRunning(int limit) {
        return stepRunRepository.listExpiredRunning(limit);
    }

    public List<ExecutionStepRunEntity> listByRunId(String runId) {
        return stepRunRepository.findByRunId(runId);
    }

    public List<ExecutionStepRunEntity> listByRunIdAndPlanVersion(String runId, Integer planVersion) {
        List<ExecutionStepRunEntity> all = stepRunRepository.findByRunId(runId);
        if (all == null || all.isEmpty() || planVersion == null || planVersion <= 0) {
            return all;
        }
        return all.stream()
                .filter(item -> item != null && item.getPlanVersion() != null && item.getPlanVersion().equals(planVersion))
                .toList();
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
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("attempt", entity.getAttempt() == null ? 0 : entity.getAttempt());
        view.put("maxRetries", entity.getMaxRetries() == null ? 0 : entity.getMaxRetries());
        view.put("status", Objects.toString(entity.getStatus(), ""));
        view.put("recoveryAttempt", entity.getRecoveryAttempt() == null ? 0 : entity.getRecoveryAttempt());
        view.put("maxRecoveryAttempts", entity.getMaxRecoveryAttempts() == null ? 0 : entity.getMaxRecoveryAttempts());
        view.put("capabilityName", nullToEmpty(entity.getCapabilityName()));
        view.put("activeCapabilityName", nullToEmpty(entity.getActiveCapabilityName()));
        view.put("selectedTool", nullToEmpty(entity.getSelectedTool()));
        view.put("selectionReasonCode", nullToEmpty(entity.getSelectionReasonCode()));
        view.put("planVersion", entity.getPlanVersion() == null ? 1 : entity.getPlanVersion());
        view.put("replanCountStep", entity.getReplanCountStep() == null ? 0 : entity.getReplanCountStep());
        view.put("lastReplanReasonCode", nullToEmpty(entity.getLastReplanReasonCode()));
        view.put("replanDecisionAction", nullToEmpty(entity.getReplanDecisionAction()));
        return view;
    }

    public String getEffectiveCapability(String runId, String stepId) {
        ExecutionStepRunEntity entity = findStepRun(runId, stepId);
        if (entity == null) {
            return null;
        }
        if (entity.getSelectedTool() != null && !entity.getSelectedTool().isBlank()) {
            return entity.getSelectedTool();
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

    private List<String> mergeFallbackTools(WorkflowStep step, ExecutionStepRunEntity entity) {
        List<String> merged = new ArrayList<>();
        for (String fallback : resolveFallbackTools(step)) {
            if (!merged.contains(fallback)) {
                merged.add(fallback);
            }
        }
        for (String fallback : readFallbackTools(entity == null ? null : entity.getFallbackToolsJson())) {
            if (!merged.contains(fallback)) {
                merged.add(fallback);
            }
        }
        return merged;
    }

    private List<String> buildOrderedCandidates(String primaryTool, List<String> fallbackTools) {
        List<String> ordered = new ArrayList<>();
        if (primaryTool != null && !primaryTool.isBlank()) {
            ordered.add(primaryTool);
        }
        if (fallbackTools != null) {
            for (String fallback : fallbackTools) {
                if (fallback != null && !fallback.isBlank() && !ordered.contains(fallback)) {
                    ordered.add(fallback);
                }
            }
        }
        return ordered;
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

    private boolean updateSelectionSnapshotWithRetry(String runId,
                                                     String stepId,
                                                     String selectedTool,
                                                     String selectionReasonCode,
                                                     String circuitStateSnapshot,
                                                     String fallbackCandidatesJson) {
        // 状态迁移写入：使用有限次 read-modify-write 重试，避免并发下丢失关键选择快照。
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            ExecutionStepRunEntity current = stepRunRepository.findByRunIdAndStepId(runId, stepId);
            if (current == null) {
                return false;
            }
            int rows = stepRunRepository.updateSelectionSnapshotWithCas(
                    runId,
                    stepId,
                    defaultVersion(current.getLockVersion()),
                    selectedTool,
                    selectionReasonCode,
                    circuitStateSnapshot,
                    fallbackCandidatesJson
            );
            if (rows > 0) {
                return true;
            }
        }
        log.warn("[step-claim] 选择快照写入失败：CAS 重试耗尽|runId={} |stepId={} |selectedTool={} |reasonCode={}",
                runId, stepId, selectedTool, selectionReasonCode);
        return false;
    }

    private boolean updateActiveSelectionSnapshotWithRetry(String runId,
                                                           String stepId,
                                                           String activeCapabilityName,
                                                           String selectedTool,
                                                           String selectionReasonCode,
                                                           String circuitStateSnapshot,
                                                           String fallbackCandidatesJson) {
        // activeCapability 与 selectedTool 需要同事务语义更新，失败时同样有限重试。
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            ExecutionStepRunEntity current = stepRunRepository.findByRunIdAndStepId(runId, stepId);
            if (current == null) {
                return false;
            }
            int rows = stepRunRepository.updateActiveSelectionSnapshotWithCas(
                    runId,
                    stepId,
                    defaultVersion(current.getLockVersion()),
                    activeCapabilityName,
                    selectedTool,
                    selectionReasonCode,
                    circuitStateSnapshot,
                    fallbackCandidatesJson
            );
            if (rows > 0) {
                return true;
            }
        }
        log.warn("[step-claim] 活跃工具快照写入失败：CAS 重试耗尽|runId={} |stepId={} |activeTool={} |selectedTool={}",
                runId, stepId, activeCapabilityName, selectedTool);
        return false;
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

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String deriveFallbackUnavailableReason(String reasonCode) {
        // 仅保留可排障的专用原因，其余统一归并为 fallback_unavailable。
        String normalized = reasonCode == null ? "" : reasonCode.toLowerCase(Locale.ROOT);
        if (normalized.contains("cas_exhausted")) {
            return "fallback_cas_exhausted";
        }
        return "fallback_unavailable";
    }
}
