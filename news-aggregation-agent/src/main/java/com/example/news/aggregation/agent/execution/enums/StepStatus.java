package com.example.news.aggregation.agent.execution.enums;

/**
 * step 执行状态枚举。
 */
public enum StepStatus {
    PENDING,
    RUNNING,
    WAITING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    SUPERSEDED
}
