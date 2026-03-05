package com.example.news.aggregation.agent.execution.service;

import com.example.news.aggregation.agent.execution.config.ExecutionPersistenceProperties;
import com.example.news.aggregation.agent.execution.domain.ExecutionEffectLatchEntity;
import com.example.news.aggregation.agent.execution.domain.ExecutionStepRunEntity;
import com.example.news.aggregation.agent.execution.enums.EffectStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Recovery worker that scans expired RUNNING steps and drives safe resume actions.
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
        log.info("[execution-recovery] found expired RUNNING steps|count={}", expired.size());
        for (ExecutionStepRunEntity step : expired) {
            if (tryHandleUnknownEffect(step)) {
                continue;
            }

            RecoveryAction action = decideRecoveryAction(step);
            log.info("[execution-recovery] recovery action decided|runId={} |stepId={} |action={} |reasonCode={} |errorCode={}",
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
                String selectedFallbackTool = stepClaimService.scheduleFallbackRetry(
                        step.getRunId(),
                        step.getStepId(),
                        "fallback_tool",
                        "FALLBACK_TOOL",
                        "fallback requested by recovery"
                );
                if (selectedFallbackTool == null || selectedFallbackTool.isBlank()) {
                    stepClaimService.markFailed(
                            step.getRunId(),
                            step.getStepId(),
                            "fallback_unavailable",
                            "FALLBACK_UNAVAILABLE",
                            "fallback cannot be scheduled"
                    );
                    executionRunService.markFailed(
                            step.getRunId(),
                            "FALLBACK_UNAVAILABLE",
                            "fallback cannot be scheduled"
                    );
                } else {
                    log.info("[execution-recovery] fallback retry scheduled|runId={} |stepId={} |selectedTool={}",
                            step.getRunId(), step.getStepId(), selectedFallbackTool);
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
                    log.error("[execution-recovery] recovery dispatch failed, rollback to WAITING|runId={} |stepId={}",
                            step.getRunId(), step.getStepId());
                }
                continue;
            }
            if (!recovered) {
                log.warn("[execution-recovery] recover failed|runId={} |stepId={} |status={} |recoveryAttempt={}",
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
        return step == null || step.getReplanAllowed() == null || step.getReplanAllowed();
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
        log.info("[execution-recovery] found UNKNOWN effect, querying provider|runId={} |stepId={} |effectKey={} |queryResult={}",
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
                    log.warn("[execution-recovery] UNKNOWN effect confirmed APPLIED but step mark succeeded failed|runId={} |stepId={}",
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
