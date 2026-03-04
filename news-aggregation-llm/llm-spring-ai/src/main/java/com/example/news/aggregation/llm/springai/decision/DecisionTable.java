package com.example.news.aggregation.llm.springai.decision;

import com.example.news.aggregation.llm.springai.contract.ExecutionEnums;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 失败决策表。
 * 先按错误类别分组，再在组内返回唯一动作。
 */
@Slf4j
@Component
public class DecisionTable {

    /**
     * 决策入口。
     * 输入为 FailureContext，输出为唯一可执行动作(action)和下一状态(nextState)。
     */
    public DecisionResult resolve(FailureContext context) {
        if (context == null || context.getErrorCategory() == null) {
            DecisionResult invalid = fail("invalid_context", context);
            log.warn("[decision] 决策输入无效：context={}, action={}, nextState={}",
                    context, invalid.getAction(), invalid.getNextState());
            return invalid;
        }

        DecisionResult result = switch (context.getErrorCategory()) {
            case NEED_USER_INPUT -> waitInput(context, "need_user_input");
            case POLICY_QUOTA_AUTH -> abort(context, "policy_quota_auth");
            case QUALITY_FAIL -> qualityDecision(context);
            case RETRYABLE_TOOL_ERROR -> retryableToolDecision(context);
        };
        log.info("[decision] 决策完成：category={}, retry={}/{}, sideEffect={}, effectState={}, action={}, nextState={}, reason={}",
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

    /**
     * 可重试工具错误的决策分流：
     * WAIT(外部信号/副作用未知) -> RETRY -> FALLBACK_TOOL -> REPLAN -> ABORT。
     */
    private DecisionResult retryableToolDecision(FailureContext context) {
        if (shouldWaitForExternalSignal(context)) {
            return waitInput(context, "need_external_signal");
        }
        if (isWriteSideEffectUnknown(context)) {
            return waitInput(context, "write_effect_unknown_need_query");
        }
        if (canRetry(context)) {
            return DecisionResult.builder()
                    .action(ExecutionEnums.DecisionAction.RETRY)
                    .nextState(ExecutionEnums.NextState.RUNNING)
                    .reasonCode("retryable_tool_error")
                    .resumeMode(resolveResumeMode(context))
                    .build();
        }
        if (context.isHasFallbackTool()) {
            return DecisionResult.builder()
                    .action(ExecutionEnums.DecisionAction.FALLBACK_TOOL)
                    .nextState(ExecutionEnums.NextState.RUNNING)
                    .reasonCode("fallback_tool")
                    .resumeMode(resolveResumeMode(context))
                    .build();
        }
        if (context.isReplanAllowed()) {
            return DecisionResult.builder()
                    .action(ExecutionEnums.DecisionAction.REPLAN)
                    .nextState(ExecutionEnums.NextState.RUNNING)
                    .reasonCode("replan_after_retry_exhausted")
                    .resumeMode(resolveResumeMode(context))
                    .build();
        }
        return fail("retry_exhausted", context);
    }

    private DecisionResult qualityDecision(FailureContext context) {
        if (context.isReplanAllowed()) {
            return DecisionResult.builder()
                    .action(ExecutionEnums.DecisionAction.REPLAN)
                    .nextState(ExecutionEnums.NextState.RUNNING)
                    .reasonCode("quality_fail_replan")
                    .resumeMode(resolveResumeMode(context))
                    .build();
        }
        return fail("quality_fail", context);
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
