package com.example.news.aggregation.agent.execution.service;

import com.example.news.aggregation.agent.execution.config.ExecutionPersistenceProperties;
import com.example.news.aggregation.agent.execution.domain.ExecutionEffectLatchEntity;
import com.example.news.aggregation.agent.execution.domain.ExecutionStepRunEntity;
import com.example.news.aggregation.agent.execution.enums.EffectStatus;
import com.example.news.aggregation.llm.springai.contract.ExecutionEnums;
import com.example.news.aggregation.llm.springai.decision.DecisionResult;
import com.example.news.aggregation.llm.springai.decision.DecisionTable;
import com.example.news.aggregation.llm.springai.decision.FailureContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

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
                stepClaimService.markFailed(
                        step.getRunId(),
                        step.getStepId(),
                        "replan_required",
                        "REPLAN_REQUIRED",
                        "quality failure requires replan"
                );
                executionRunService.markFailed(
                        step.getRunId(),
                        "REPLAN_REQUIRED",
                        "quality failure requires replan"
                );
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
            stepClaimService.markFailed(
                    step.getRunId(),
                    step.getStepId(),
                    fallbackReasonCode,
                    "REPLAN_REQUIRED",
                    "fallback cannot be scheduled, replan required"
            );
            executionRunService.markFailed(
                    step.getRunId(),
                    "REPLAN_REQUIRED",
                    "fallback cannot be scheduled"
            );
            log.info("[execution-recovery] fallback 不可用后转 REPLAN|runId={} |stepId={} |reasonCode={}",
                    step.getRunId(), step.getStepId(), fallbackReasonCode);
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

    private enum RecoveryAction {
        RETRY,
        FALLBACK,
        WAIT,
        REPLAN,
        ABORT
    }
}
