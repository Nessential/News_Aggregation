package com.example.news.aggregation.llm.springai.decision;

import com.example.news.aggregation.llm.springai.contract.ExecutionEnums;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 失败决策表：保证同一输入得到稳定、可解释的动作输出。
 */
@Slf4j
@Component
public class DecisionTable {

    private static final String RC_SCHEMA_UNSUPPORTED_COMPAT_RESTRICTED = "schema_version_unsupported_compat_restricted";
    private static final String RC_OUTPUT_SCHEMA_VERSION_MISMATCH = "output_schema_version_mismatch";
    private static final String RC_OUTPUT_MISSING_REQUIRED_FIELD_STABLE = "output_missing_required_field_stable";
    private static final String RC_OUTPUT_TYPE_MISMATCH_STABLE = "output_type_mismatch_stable";
    private static final String RC_OUTPUT_PARSE_ERROR = "output_parse_error";
    private static final String RC_OUTPUT_MALFORMED_TEMPORARY = "output_malformed_temporary";
    private static final String RC_DONE_CHECK_FAIL = "done_check_fail";
    private static final String RC_FALLBACK_UNAVAILABLE = "fallback_unavailable";

    public DecisionResult resolve(FailureContext context) {
        if (context == null || context.getErrorCategory() == null) {
            DecisionResult invalid = fail("invalid_context", context);
            log.warn("[decision] 决策上下文无效|context={} |action={} |nextState={}",
                    context, invalid.getAction(), invalid.getNextState());
            return invalid;
        }

        if (RC_FALLBACK_UNAVAILABLE.equals(context.getFailureReasonCode())) {
            DecisionResult mapped = fallbackUnavailableDecision(context);
            log.info("[decision] fallback_unavailable 映射完成|needsExternalSignal={} |replanAllowed={} |action={} |nextState={} |reason={}",
                    context.isNeedsExternalSignal(),
                    context.isReplanAllowed(),
                    mapped.getAction(),
                    mapped.getNextState(),
                    mapped.getReasonCode());
            return mapped;
        }

        DecisionResult result = switch (context.getErrorCategory()) {
            case NEED_USER_INPUT -> waitInput(context, "need_user_input");
            case POLICY_QUOTA_AUTH -> abort(context, "policy_quota_auth");
            case QUALITY_FAIL -> qualityDecision(context);
            case RETRYABLE_TOOL_ERROR -> retryableToolDecision(context);
        };
        log.info("[decision] 决策完成|category={} |retry={}/{} |sideEffect={} |effectState={} |action={} |nextState={} |reason={}",
                context.getErrorCategory(),
                context.getRetryCount(),
                context.getMaxRetries(),
                context.getSideEffect(),
                context.getEffectState(),
                result.getAction(),
                result.getNextState(),
                result.getReasonCode());
        return result;
    }

    private DecisionResult fallbackUnavailableDecision(FailureContext context) {
        if (context != null && context.isNeedsExternalSignal()) {
            return waitInput(context, RC_FALLBACK_UNAVAILABLE);
        }
        if (context != null && context.isReplanAllowed()) {
            return replanWithGuards(context, RC_FALLBACK_UNAVAILABLE);
        }
        return abort(context, RC_FALLBACK_UNAVAILABLE);
    }

    private DecisionResult retryableToolDecision(FailureContext context) {
        String reason = context.getFailureReasonCode();

        if (RC_OUTPUT_SCHEMA_VERSION_MISMATCH.equals(reason)
                || RC_OUTPUT_MISSING_REQUIRED_FIELD_STABLE.equals(reason)
                || RC_OUTPUT_TYPE_MISMATCH_STABLE.equals(reason)) {
            if (context.isHasFallbackTool()) {
                return fallback(context, "fallback_tool_for_stable_output_schema_error");
            }
            if (context.isReplanAllowed()) {
                return replanWithGuards(context, "replan_for_stable_output_schema_error");
            }
            return fail("stable_output_schema_error_abort", context);
        }

        if (RC_OUTPUT_PARSE_ERROR.equals(reason) || RC_OUTPUT_MALFORMED_TEMPORARY.equals(reason)) {
            if (canRetry(context)) {
                return retry(context, reason);
            }
            if (context.isHasFallbackTool()) {
                return fallback(context, "fallback_after_output_parse_retry_exhausted");
            }
            if (context.isReplanAllowed()) {
                return replanWithGuards(context, "replan_after_output_parse_retry_exhausted");
            }
            return fail("output_parse_retry_exhausted", context);
        }

        if (shouldWaitForExternalSignal(context)) {
            return waitInput(context, "need_external_signal");
        }
        if (isWriteSideEffectUnknown(context)) {
            return waitInput(context, "write_effect_unknown_need_query");
        }
        if (canRetry(context)) {
            return retry(context, "retryable_tool_error");
        }
        if (context.isHasFallbackTool()) {
            return fallback(context, "fallback_tool");
        }
        if (context.isReplanAllowed()) {
            return replanWithGuards(context, "replan_after_retry_exhausted");
        }
        return fail("retry_exhausted", context);
    }

    private DecisionResult qualityDecision(FailureContext context) {
        String reason = context.getFailureReasonCode();

        if (RC_SCHEMA_UNSUPPORTED_COMPAT_RESTRICTED.equals(reason)
                || RC_OUTPUT_SCHEMA_VERSION_MISMATCH.equals(reason)) {
            return degrade(context, "degrade_output_for_schema_incompatible");
        }

        if (RC_DONE_CHECK_FAIL.equals(reason)) {
            if (context.isReplanAllowed()) {
                return replanWithGuards(context, "quality_fail_replan");
            }
            return degrade(context, "degrade_output_for_done_check_fail");
        }

        if (context.isReplanAllowed()) {
            return replanWithGuards(context, "quality_fail_replan");
        }
        return fail("quality_fail", context);
    }

    private DecisionResult replanWithGuards(FailureContext context, String reason) {
        DecisionResult blocked = guardReplan(context);
        if (blocked != null) {
            return blocked;
        }
        return replan(context, reason);
    }

    private DecisionResult guardReplan(FailureContext context) {
        if (context == null) {
            return abort(null, "replan_invalid_context");
        }
        if (Boolean.TRUE.equals(context.getReplanNonRetryableReason())) {
            return abort(context, "replan_non_retryable_reason");
        }
        if (Boolean.TRUE.equals(context.getReplanNoEffectiveChange())) {
            return abort(context, "replan_no_effective_change");
        }
        if (isReplanHardLimitReached(context)) {
            return abort(context, "replan_hard_limit_reached");
        }
        if (isReplanBudgetExhaustedRun(context)) {
            return abort(context, "replan_budget_exhausted_run");
        }
        if (isReplanBudgetExhaustedStep(context)) {
            return abort(context, "replan_budget_exhausted_step");
        }
        if (Boolean.FALSE.equals(context.getEvidenceSufficient())) {
            if (context.isNeedsExternalSignal()) {
                return waitInput(context, "evidence_insufficient_wait");
            }
            return abort(context, "evidence_insufficient_abort");
        }
        return null;
    }

    private boolean isReplanBudgetExhaustedRun(FailureContext context) {
        if (context.getMaxReplansPerRun() == null || context.getMaxReplansPerRun() < 0) {
            return false;
        }
        int current = context.getReplanCountRun() == null ? 0 : Math.max(0, context.getReplanCountRun());
        return current >= context.getMaxReplansPerRun();
    }

    private boolean isReplanBudgetExhaustedStep(FailureContext context) {
        if (context.getMaxReplansPerStep() == null || context.getMaxReplansPerStep() < 0) {
            return false;
        }
        int current = context.getReplanCountStep() == null ? 0 : Math.max(0, context.getReplanCountStep());
        return current >= context.getMaxReplansPerStep();
    }

    private boolean isReplanHardLimitReached(FailureContext context) {
        if (context == null) {
            return false;
        }
        if (context.getMaxSteps() != null && context.getMaxSteps() >= 0) {
            int currentSteps = context.getStepCount() == null ? 0 : Math.max(0, context.getStepCount());
            if (currentSteps >= context.getMaxSteps()) {
                return true;
            }
        }
        if (context.getTimeoutMs() != null && context.getTimeoutMs() >= 0) {
            long elapsed = context.getElapsedMs() == null ? 0L : Math.max(0L, context.getElapsedMs());
            return elapsed >= context.getTimeoutMs();
        }
        return false;
    }

    private DecisionResult retry(FailureContext context, String reason) {
        return DecisionResult.builder()
                .action(ExecutionEnums.DecisionAction.RETRY)
                .nextState(ExecutionEnums.NextState.RUNNING)
                .reasonCode(reason)
                .resumeMode(resolveResumeMode(context))
                .build();
    }

    private DecisionResult fallback(FailureContext context, String reason) {
        return DecisionResult.builder()
                .action(ExecutionEnums.DecisionAction.FALLBACK_TOOL)
                .nextState(ExecutionEnums.NextState.RUNNING)
                .reasonCode(reason)
                .resumeMode(resolveResumeMode(context))
                .build();
    }

    private DecisionResult replan(FailureContext context, String reason) {
        return DecisionResult.builder()
                .action(ExecutionEnums.DecisionAction.REPLAN)
                .nextState(ExecutionEnums.NextState.RUNNING)
                .reasonCode(reason)
                .resumeMode(resolveResumeMode(context))
                .build();
    }

    private DecisionResult degrade(FailureContext context, String reason) {
        return DecisionResult.builder()
                .action(ExecutionEnums.DecisionAction.DEGRADE_OUTPUT)
                .nextState(ExecutionEnums.NextState.SUCCEEDED)
                .reasonCode(reason)
                .resumeMode(resolveResumeMode(context))
                .build();
    }

    private DecisionResult waitInput(FailureContext context, String reason) {
        return DecisionResult.builder()
                .action(ExecutionEnums.DecisionAction.WAIT)
                .nextState(ExecutionEnums.NextState.WAITING)
                .reasonCode(reason)
                .resumeMode(resolveResumeMode(context))
                .build();
    }

    private DecisionResult abort(FailureContext context, String reason) {
        return DecisionResult.builder()
                .action(ExecutionEnums.DecisionAction.ABORT)
                .nextState(ExecutionEnums.NextState.FAILED)
                .reasonCode(reason)
                .resumeMode(resolveResumeMode(context))
                .build();
    }

    private DecisionResult fail(String reason, FailureContext context) {
        return DecisionResult.builder()
                .action(ExecutionEnums.DecisionAction.ABORT)
                .nextState(ExecutionEnums.NextState.FAILED)
                .reasonCode(reason)
                .resumeMode(resolveResumeMode(context))
                .build();
    }

    private boolean shouldWaitForExternalSignal(FailureContext context) {
        return context.isNeedsExternalSignal();
    }

    private boolean isWriteSideEffectUnknown(FailureContext context) {
        return context.getSideEffect() == ExecutionEnums.SideEffectType.WRITE
                && context.getEffectState() == ExecutionEnums.EffectState.UNKNOWN;
    }

    private boolean canRetry(FailureContext context) {
        int retries = context.getRetryCount() != null ? context.getRetryCount() : 0;
        int max = context.getMaxRetries() != null ? context.getMaxRetries() : 0;
        if (retries >= max) {
            return false;
        }
        if (context.getSideEffect() != ExecutionEnums.SideEffectType.WRITE) {
            return true;
        }
        return context.getEffectState() == ExecutionEnums.EffectState.NOT_APPLIED;
    }

    private ExecutionEnums.ResumeMode resolveResumeMode(FailureContext context) {
        if (context == null || context.getPreferredResumeMode() == null) {
            return ExecutionEnums.ResumeMode.CONTINUE;
        }
        return context.getPreferredResumeMode();
    }
}
