package com.example.news.aggregation.agent.execution.service;

import com.example.news.aggregation.agent.client.PlannerClient;
import com.example.news.aggregation.agent.execution.config.ExecutionPersistenceProperties;
import com.example.news.aggregation.agent.execution.config.ReplanControlProperties;
import com.example.news.aggregation.agent.execution.domain.ExecutionEffectLatchEntity;
import com.example.news.aggregation.agent.execution.domain.ExecutionRunEntity;
import com.example.news.aggregation.agent.execution.domain.ExecutionStepRunEntity;
import com.example.news.aggregation.agent.execution.enums.EffectStatus;
import com.example.news.aggregation.llm.springai.contract.ExecutionEnums;
import com.example.news.aggregation.llm.springai.contract.ExecutionPlan;
import com.example.news.aggregation.llm.springai.contract.PlanRequest;
import com.example.news.aggregation.llm.springai.decision.DecisionResult;
import com.example.news.aggregation.llm.springai.decision.DecisionTable;
import com.example.news.aggregation.llm.springai.decision.FailureContext;
import com.example.news.aggregation.llm.springai.state.PlannerState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 恢复 worker：扫描过期 RUNNING 步骤并执行安全恢复动作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionRecoveryService {

    private final ExecutionPersistenceProperties properties;
    private final StepClaimService stepClaimService;
    private final EffectLatchService effectLatchService;
    private final EffectQueryGateway effectQueryGateway;
    private final ExecutionRunService executionRunService;
    private final ExecutionDispatchService executionDispatchService;
    private final DecisionTable decisionTable;
    private final ReplanControlProperties replanControlProperties;
    private final PlannerClient plannerClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Scheduled(fixedDelayString = "${app.agent.execution.recovery.scan-interval-ms:15000}")
    public void recoverExpiredSteps() {
        if (!properties.getRecovery().isEnabled()) {
            return;
        }
        List<ExecutionStepRunEntity> expired = stepClaimService.listExpiredRunning(
                properties.getRecovery().getScanBatchSize()
        );
        if (expired == null || expired.isEmpty()) {
            return;
        }
        log.info("[execution-recovery] 扫描到过期 RUNNING 步骤|count={}", expired.size());
        for (ExecutionStepRunEntity step : expired) {
            if (tryHandleUnknownEffect(step)) {
                continue;
            }

            RecoveryAction action = decideRecoveryAction(step);
            log.info("[execution-recovery] 恢复动作已决策|runId={} |stepId={} |action={} |reasonCode={} |errorCode={}",
                    step.getRunId(), step.getStepId(), action, step.getReasonCode(), step.getErrorCode());

            if (action == RecoveryAction.WAIT) {
                stepClaimService.markWaiting(
                        step.getRunId(),
                        step.getStepId(),
                        "need_user_input",
                        "NEED_USER_INPUT",
                        "recovery requires external/user input"
                );
                executionRunService.markWaiting(
                        step.getRunId(),
                        step.getStepId(),
                        "need_user_input",
                        "recovery requires external/user input"
                );
                continue;
            }

            if (action == RecoveryAction.REPLAN) {
                String finalReason = persistReplanAttempt(step, "replan_required");
                boolean replanSucceeded = triggerReplan(step, finalReason);
                if (!replanSucceeded) {
                    stepClaimService.markFailed(
                            step.getRunId(),
                            step.getStepId(),
                            finalReason,
                            "REPLAN_FAILED",
                            "replan triggered but planner returned empty plan"
                    );
                    executionRunService.markFailed(
                            step.getRunId(),
                            "REPLAN_FAILED",
                            "replan triggered but planner returned empty plan"
                    );
                }
                continue;
            }

            if (action == RecoveryAction.ABORT) {
                stepClaimService.markFailed(
                        step.getRunId(),
                        step.getStepId(),
                        "policy_quota_auth",
                        "POLICY_QUOTA_AUTH",
                        "recovery aborted by policy/quota/auth"
                );
                executionRunService.markAborted(
                        step.getRunId(),
                        "POLICY_QUOTA_AUTH",
                        "recovery aborted by policy/quota/auth"
                );
                continue;
            }

            if (action == RecoveryAction.FALLBACK) {
                StepClaimService.FallbackScheduleResult fallbackResult = stepClaimService.scheduleFallbackRetryDetailed(
                        step.getRunId(),
                        step.getStepId(),
                        "fallback_tool",
                        "FALLBACK_TOOL",
                        "fallback requested by recovery"
                );
                if (!fallbackResult.hasSelectedTool()) {
                    handleFallbackUnavailable(step, fallbackResult.failureReasonCode());
                } else {
                    log.info("[execution-recovery] 已安排回退重试|runId={} |stepId={} |selectedTool={}",
                            step.getRunId(), step.getStepId(), fallbackResult.selectedTool());
                }
                continue;
            }

            boolean exhaustedBeforeRecover = isRecoveryExhausted(step);
            boolean recovered = stepClaimService.recoverExpiredStep(step);
            if (recovered && exhaustedBeforeRecover) {
                executionRunService.markWaiting(
                        step.getRunId(),
                        step.getStepId(),
                        "recovery_exhausted",
                        "recovery attempts exhausted"
                );
                continue;
            }
            if (recovered) {
                boolean dispatched = executionDispatchService.dispatchRun(step.getRunId(), step.getStepId(), null);
                if (!dispatched) {
                    stepClaimService.markWaiting(
                            step.getRunId(),
                            step.getStepId(),
                            "recovery_dispatch_failed",
                            "RECOVERY_DISPATCH_FAILED",
                            "recovered step dispatch failed"
                    );
                    executionRunService.markWaiting(
                            step.getRunId(),
                            step.getStepId(),
                            "recovery_dispatch_failed",
                            "recovered step dispatch failed"
                    );
                    log.error("[execution-recovery] 恢复后重新派发失败，已回滚到 WAITING|runId={} |stepId={}",
                            step.getRunId(), step.getStepId());
                }
                continue;
            }
            if (!recovered) {
                log.warn("[execution-recovery] 步骤恢复失败|runId={} |stepId={} |status={} |recoveryAttempt={}",
                        step.getRunId(),
                        step.getStepId(),
                        step.getStatus(),
                        step.getRecoveryAttempt());
            }
        }
    }

    private boolean isRecoveryExhausted(ExecutionStepRunEntity step) {
        if (step == null) {
            return false;
        }
        int currentRecovery = step.getRecoveryAttempt() == null ? 0 : step.getRecoveryAttempt();
        int maxRecovery = step.getMaxRecoveryAttempts() == null
                ? properties.getRecovery().getMaxRecoveryAttempts()
                : step.getMaxRecoveryAttempts();
        return currentRecovery + 1 > maxRecovery;
    }

    private RecoveryAction decideRecoveryAction(ExecutionStepRunEntity step) {
        if (Boolean.TRUE.equals(step == null ? null : step.getNeedUserInputOnFailure())) {
            return RecoveryAction.WAIT;
        }

        if (hasPolicyOrQuotaError(step)) {
            return RecoveryAction.ABORT;
        }

        if (isQualityFailure(step)) {
            if (!isReplanAllowed(step) && hasFallbackTool(step)) {
                return RecoveryAction.FALLBACK;
            }
            if (isReplanAllowed(step)) {
                return RecoveryAction.REPLAN;
            }
        }

        if (hasFallbackTrigger(step) && hasFallbackTool(step)) {
            return RecoveryAction.FALLBACK;
        }

        if (isNeedUserInputReason(step)) {
            return RecoveryAction.WAIT;
        }

        if (isQualityFailure(step) && hasFallbackTool(step)) {
            return RecoveryAction.FALLBACK;
        }

        if (isQualityFailure(step) && isReplanAllowed(step)) {
            return RecoveryAction.REPLAN;
        }

        String reason = lower(step == null ? null : step.getReasonCode());
        String error = lower(step == null ? null : step.getErrorCode());
        if (containsAny(reason, error, "missing_required_input", "need_user_input", "need_user_clarification", "clarification")) {
            return RecoveryAction.WAIT;
        }
        if (containsAny(reason, error, "unauthorized", "forbidden", "quota", "policy", "auth")) {
            return RecoveryAction.ABORT;
        }
        if (containsAny(reason, error, "done_check_fail", "output_missing_required_field_stable",
                "output_type_mismatch_stable", "output_schema_version_mismatch",
                "schema_version_unsupported_compat_restricted")) {
            return isReplanAllowed(step) ? RecoveryAction.REPLAN : RecoveryAction.RETRY;
        }
        if (containsAny(reason, error, "output_parse_error", "output_malformed_temporary", "fallback_tool")
                && hasFallbackTool(step)) {
            return RecoveryAction.FALLBACK;
        }

        return RecoveryAction.RETRY;
    }

    private void handleFallbackUnavailable(ExecutionStepRunEntity step, String unavailableReasonCode) {
        if (step == null) {
            return;
        }
        DecisionResult decision = decideFallbackUnavailable(step);
        String fallbackReasonCode = normalizeFallbackUnavailableReason(unavailableReasonCode);
        log.warn("[execution-recovery] 恢复阶段 fallback 不可调度|runId={} |stepId={} |reasonCode={} |action={}",
                step.getRunId(), step.getStepId(), fallbackReasonCode, decision == null ? null : decision.getAction());
        if (decision != null && decision.getAction() == ExecutionEnums.DecisionAction.WAIT) {
            stepClaimService.markWaiting(
                    step.getRunId(),
                    step.getStepId(),
                    fallbackReasonCode,
                    "FALLBACK_UNAVAILABLE",
                    "fallback cannot be scheduled, need user input"
            );
            executionRunService.markWaiting(
                    step.getRunId(),
                    step.getStepId(),
                    fallbackReasonCode,
                    "fallback cannot be scheduled"
            );
            log.info("[execution-recovery] fallback 不可用后转 WAITING|runId={} |stepId={} |reasonCode={}",
                    step.getRunId(), step.getStepId(), fallbackReasonCode);
            return;
        }
        if (decision != null && decision.getAction() == ExecutionEnums.DecisionAction.REPLAN) {
            String finalReason = persistReplanAttempt(step, fallbackReasonCode);
            stepClaimService.markFailed(
                    step.getRunId(),
                    step.getStepId(),
                    finalReason,
                    "REPLAN_REQUIRED",
                    "fallback cannot be scheduled, replan required"
            );
            executionRunService.markFailed(
                    step.getRunId(),
                    "REPLAN_REQUIRED",
                    "fallback cannot be scheduled"
            );
            log.info("[execution-recovery] fallback 不可用后转 REPLAN|runId={} |stepId={} |reasonCode={}",
                    step.getRunId(), step.getStepId(), finalReason);
            return;
        }
        stepClaimService.markFailed(
                step.getRunId(),
                step.getStepId(),
                fallbackReasonCode,
                "FALLBACK_UNAVAILABLE",
                "fallback cannot be scheduled"
        );
        executionRunService.markAborted(
                step.getRunId(),
                "FALLBACK_UNAVAILABLE",
                "fallback cannot be scheduled"
        );
        log.warn("[execution-recovery] fallback 不可用后执行 ABORT|runId={} |stepId={} |reasonCode={}",
                step.getRunId(), step.getStepId(), fallbackReasonCode);
    }

    private DecisionResult decideFallbackUnavailable(ExecutionStepRunEntity step) {
        int maxRetries = step.getMaxRetries() == null ? 0 : Math.max(0, step.getMaxRetries());
        FailureContext context = FailureContext.builder()
                .errorCategory(ExecutionEnums.ErrorCategory.RETRYABLE_TOOL_ERROR)
                .failureReasonCode("fallback_unavailable")
                .retryCount(maxRetries)
                .maxRetries(maxRetries)
                .hasFallbackTool(false)
                .replanAllowed(Boolean.TRUE.equals(step.getReplanAllowed()))
                .replanFeatureEnabled(replanControlProperties.isEnabled())
                .needsExternalSignal(Boolean.TRUE.equals(step.getNeedUserInputOnFailure()))
                .sideEffect(parseSideEffect(step.getSideEffect()))
                .effectState(parseEffectState(step.getSideEffect()))
                .preferredResumeMode(parseResumeMode(step.getResumeMode()))
                .build();
        return decisionTable.resolve(context);
    }

    private String normalizeFallbackUnavailableReason(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return "fallback_unavailable";
        }
        if ("fallback_cas_exhausted".equals(reasonCode)) {
            return reasonCode;
        }
        return "fallback_unavailable";
    }

    private boolean hasFallbackTrigger(ExecutionStepRunEntity step) {
        String reason = lower(step == null ? null : step.getReasonCode());
        String error = lower(step == null ? null : step.getErrorCode());
        return containsAny(reason, error, "output_parse_error", "output_malformed_temporary", "fallback_tool");
    }

    private boolean hasPolicyOrQuotaError(ExecutionStepRunEntity step) {
        String reason = lower(step == null ? null : step.getReasonCode());
        String error = lower(step == null ? null : step.getErrorCode());
        return containsAny(reason, error, "unauthorized", "forbidden", "quota", "policy", "auth");
    }

    private boolean isNeedUserInputReason(ExecutionStepRunEntity step) {
        String reason = lower(step == null ? null : step.getReasonCode());
        String error = lower(step == null ? null : step.getErrorCode());
        return containsAny(reason, error, "missing_required_input", "need_user_input", "need_user_clarification", "clarification");
    }

    private boolean isQualityFailure(ExecutionStepRunEntity step) {
        String reason = lower(step == null ? null : step.getReasonCode());
        String error = lower(step == null ? null : step.getErrorCode());
        return containsAny(reason, error,
                "done_check_fail",
                "output_missing_required_field_stable",
                "output_type_mismatch_stable",
                "output_schema_version_mismatch",
                "schema_version_unsupported_compat_restricted");
    }

    private boolean hasFallbackTool(ExecutionStepRunEntity step) {
        if (step == null || step.getFallbackToolsJson() == null) {
            return false;
        }
        String normalized = step.getFallbackToolsJson().replaceAll("\\s", "");
        return !normalized.isBlank() && !"[]".equals(normalized);
    }

    private boolean isReplanAllowed(ExecutionStepRunEntity step) {
        if (!replanControlProperties.isEnabled()) {
            return false;
        }
        return step != null && Boolean.TRUE.equals(step.getReplanAllowed());
    }

    private ExecutionEnums.SideEffectType parseSideEffect(String sideEffect) {
        if (sideEffect == null || sideEffect.isBlank()) {
            return ExecutionEnums.SideEffectType.NONE;
        }
        try {
            return ExecutionEnums.SideEffectType.valueOf(sideEffect);
        } catch (Exception ignore) {
            return ExecutionEnums.SideEffectType.NONE;
        }
    }

    private ExecutionEnums.EffectState parseEffectState(String sideEffect) {
        ExecutionEnums.SideEffectType type = parseSideEffect(sideEffect);
        if (type == ExecutionEnums.SideEffectType.WRITE || type == ExecutionEnums.SideEffectType.EXTERNAL) {
            return ExecutionEnums.EffectState.UNKNOWN;
        }
        return ExecutionEnums.EffectState.NOT_APPLIED;
    }

    private ExecutionEnums.ResumeMode parseResumeMode(String resumeMode) {
        if (resumeMode == null || resumeMode.isBlank()) {
            return ExecutionEnums.ResumeMode.CONTINUE;
        }
        try {
            return ExecutionEnums.ResumeMode.valueOf(resumeMode);
        } catch (Exception ignore) {
            return ExecutionEnums.ResumeMode.CONTINUE;
        }
    }

    private boolean containsAny(String reason, String error, String... keywords) {
        if (keywords == null || keywords.length == 0) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            String k = keyword.toLowerCase(Locale.ROOT);
            if ((reason != null && reason.contains(k)) || (error != null && error.contains(k))) {
                return true;
            }
        }
        return false;
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private boolean tryHandleUnknownEffect(ExecutionStepRunEntity step) {
        if (step == null) {
            return false;
        }
        String effectKey = effectLatchService.buildEffectKey(step.getRunId(), step.getStepId());
        ExecutionEffectLatchEntity latch = effectLatchService.findByEffectKey(effectKey);
        if (latch == null || !EffectStatus.UNKNOWN.name().equals(latch.getStatus())) {
            return false;
        }

        EffectQueryGateway.EffectQueryResult queryResult = effectQueryGateway.query(
                step.getRunId(),
                step.getStepId(),
                effectKey,
                latch.getProviderTrace()
        );
        log.info("[execution-recovery] 检测到 UNKNOWN 副作用，正在查询 provider|runId={} |stepId={} |effectKey={} |queryResult={}",
                step.getRunId(), step.getStepId(), effectKey, queryResult);

        switch (queryResult) {
            case APPLIED -> {
                effectLatchService.markApplied(step.getRunId(), step.getStepId(), effectKey, latch.getProviderTrace(),
                        "{\"recoveredBy\":\"effect_query\"}");
                boolean succeeded = stepClaimService.markSucceeded(
                        step.getRunId(),
                        step.getStepId(),
                        "{\"recoveredBy\":\"effect_query\",\"effectStatus\":\"APPLIED\"}"
                );
                if (!succeeded) {
                    log.warn("[execution-recovery] UNKNOWN 副作用确认 APPLIED，但 step 标记成功失败|runId={} |stepId={}",
                            step.getRunId(), step.getStepId());
                }
                return true;
            }
            case NOT_APPLIED -> {
                effectLatchService.markFailed(
                        step.getRunId(),
                        step.getStepId(),
                        effectKey,
                        "EFFECT_NOT_APPLIED",
                        "effect_query confirmed not applied"
                );
                return false;
            }
            case UNKNOWN, UNSUPPORTED -> {
                String reasonCode = queryResult == EffectQueryGateway.EffectQueryResult.UNKNOWN
                        ? "effect_query_uncertain"
                        : "effect_query_unsupported";
                stepClaimService.markWaiting(
                        step.getRunId(),
                        step.getStepId(),
                        reasonCode,
                        "EFFECT_QUERY_UNAVAILABLE",
                        "unknown effect cannot be safely retried"
                );
                executionRunService.markWaiting(
                        step.getRunId(),
                        step.getStepId(),
                        reasonCode,
                        "recovery waiting for external confirmation"
                );
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * 触发真实的 Replan：收集执行上下文 → 调用 Planner → 应用新计划。
     *
     * @return true 表示成功触发并应用了新计划，false 表示 Planner 返回空计划或调用失败
     */
    private boolean triggerReplan(ExecutionStepRunEntity step, String replanReason) {
        String runId = step.getRunId();
        try {
            Map<String, PlannerState.StepExecutionResult> stepResults = collectStepResults(runId);
            String originalQuery = resolveOriginalQuery(runId, stepResults);

            PlanRequest replanRequest = PlanRequest.builder()
                    .query(originalQuery)
                    .isReplan(true)
                    .replanReason(replanReason)
                    .stepResults(stepResults)
                    .build();

            log.info("[execution-recovery] 触发 Replan|runId={} |stepId={} |reason={} |completedSteps={}",
                    runId, step.getStepId(), replanReason, stepResults.size());

            ExecutionPlan newPlan = plannerClient.plan(replanRequest);

            if (newPlan == null || newPlan.getSteps() == null || newPlan.getSteps().isEmpty()) {
                log.warn("[execution-recovery] Planner 返回空计划|runId={}", runId);
                return false;
            }

            boolean applied = executionDispatchService.applyNewPlan(runId, newPlan);
            if (applied) {
                log.info("[execution-recovery] Replan 成功，新计划已应用|runId={} |newStepCount={}",
                        runId, newPlan.getSteps().size());
            } else {
                log.warn("[execution-recovery] 新计划应用失败|runId={}", runId);
            }
            return applied;

        } catch (Exception e) {
            log.error("[execution-recovery] Replan 触发异常|runId={} |error={}", runId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 收集当前 run 所有步骤的执行摘要（精简，不传完整数据，避免 token 爆炸）。
     */
    private Map<String, PlannerState.StepExecutionResult> collectStepResults(String runId) {
        List<ExecutionStepRunEntity> stepRuns = stepClaimService.listByRunId(runId);
        Map<String, PlannerState.StepExecutionResult> results = new HashMap<>();
        if (stepRuns == null) return results;

        for (ExecutionStepRunEntity s : stepRuns) {
            if (s == null || s.getStepId() == null) continue;
            String status = "RUNNING".equals(s.getStatus()) || "PENDING".equals(s.getStatus())
                    ? "FAILED" : s.getStatus();

            results.put(s.getStepId(), PlannerState.StepExecutionResult.builder()
                    .stepId(s.getStepId())
                    .status(status)
                    .toolUsed(s.getSelectedTool())
                    .failureReason(s.getLastReplanReasonCode())
                    .evidenceCount(extractEvidenceCount(s.getEvidenceSnapshot()))
                    .outputSummary(buildOutputSummary(s))
                    .build());
        }
        return results;
    }

    /**
     * 从步骤输入参数中还原原始 query。
     * 优先从第一个步骤的 inputJson 取 query 字段，取不到则返回空字符串。
     */
    private String resolveOriginalQuery(String runId,
                                        Map<String, PlannerState.StepExecutionResult> stepResults) {
        List<ExecutionStepRunEntity> stepRuns = stepClaimService.listByRunId(runId);
        if (stepRuns == null || stepRuns.isEmpty()) return "";
        for (ExecutionStepRunEntity s : stepRuns) {
            if (s == null || s.getInputJson() == null) continue;
            try {
                Map<String, Object> input = objectMapper.readValue(
                        s.getInputJson(), new TypeReference<Map<String, Object>>() {});
                Object q = input.get("query");
                if (q != null && !String.valueOf(q).isBlank()) {
                    return String.valueOf(q);
                }
            } catch (Exception ignore) {
            }
        }
        return "";
    }

    private int extractEvidenceCount(String evidenceSnapshot) {
        if (evidenceSnapshot == null || evidenceSnapshot.isBlank()) return 0;
        try {
            List<?> list = objectMapper.readValue(evidenceSnapshot, List.class);
            return list.size();
        } catch (Exception ignore) {
            return 0;
        }
    }

    private String buildOutputSummary(ExecutionStepRunEntity s) {
        if ("SUCCESS".equals(s.getStatus())) {
            int count = extractEvidenceCount(s.getEvidenceSnapshot());
            return count > 0 ? "找到 " + count + " 条证据" : "执行成功";
        }
        return s.getLastReplanReasonCode() != null ? "失败: " + s.getLastReplanReasonCode() : "执行失败";
    }

    /**
     * 恢复线程中的 Replan 审计回填：
     * 1. 扣减 run 级预算（复用 active_plan_version，保证统计一致）；
     * 2. 回填 step 级 replan 计数与原因码。
     */
    private String persistReplanAttempt(ExecutionStepRunEntity step, String reasonCode) {
        if (step == null) {
            return "replan_cas_exhausted";
        }
        String normalizedReason = reasonCode == null || reasonCode.isBlank() ? "replan_required" : reasonCode;
        ExecutionRunEntity runEntity = executionRunService.findByRunId(step.getRunId());
        int activePlanVersion = runEntity == null || runEntity.getActivePlanVersion() == null
                ? 1
                : Math.max(1, runEntity.getActivePlanVersion());
        boolean runBudgetUpdated = executionRunService.switchActivePlanVersionAndIncreaseReplanCount(
                step.getRunId(),
                activePlanVersion
        );
        if (!runBudgetUpdated) {
            log.warn("[execution-recovery] Replan 回填失败：run 预算 CAS 更新失败|runId={} |stepId={} |activePlanVersion={}",
                    step.getRunId(), step.getStepId(), activePlanVersion);
            return "replan_cas_exhausted";
        }
        boolean stepRecorded = stepClaimService.recordReplanAttempt(
                step.getRunId(),
                step.getStepId(),
                normalizedReason,
                null,
                null,
                "REPLAN"
        );
        if (!stepRecorded) {
            log.warn("[execution-recovery] Replan 回填失败：step 快照 CAS 更新失败|runId={} |stepId={} |reasonCode={}",
                    step.getRunId(), step.getStepId(), normalizedReason);
            return "replan_cas_exhausted";
        }
        return normalizedReason;
    }

    private enum RecoveryAction {
        RETRY,
        FALLBACK,
        WAIT,
        REPLAN,
        ABORT
    }
}
