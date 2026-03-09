package com.example.news.aggregation.rpc.contract;

import java.io.Serializable;

/**
 * ? */
public final class ExecutionEnums {

    private ExecutionEnums() {
    }

    public enum SideEffectType {
        NONE,
        READ,
        WRITE,
        EXTERNAL
    }

    public enum ResumeMode {
        CONTINUE,
        RESTART_STEP
    }

    public enum DecisionAction {
        RETRY,
        FALLBACK_TOOL,
        REPLAN,
        DEGRADE_OUTPUT,
        WAIT,
        ABORT
    }

    public enum NextState {
        RUNNING,
        WAITING,
        FAILED,
        SUCCEEDED
    }

    public enum ErrorCategory {
        NEED_USER_INPUT,
        RETRYABLE_TOOL_ERROR,
        QUALITY_FAIL,
        POLICY_QUOTA_AUTH
    }

    public enum EffectState {
        UNKNOWN,
        NOT_APPLIED,
        APPLIED
    }
}
