package com.example.news.aggregation.agent.workflow.selector;

/**
 * Tool failure category used by health window and circuit breaker.
 */
public enum ToolFailureCategory {
    INFRA_FAIL,
    TIMEOUT,
    SCHEMA_FAIL,
    QUALITY_FAIL,
    OTHER
}
