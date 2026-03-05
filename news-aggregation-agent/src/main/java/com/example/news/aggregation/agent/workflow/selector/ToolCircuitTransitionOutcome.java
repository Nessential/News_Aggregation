package com.example.news.aggregation.agent.workflow.selector;

/**
 * Result for circuit state transition writes.
 */
public enum ToolCircuitTransitionOutcome {
    APPLIED,
    SKIPPED,
    CAS_EXHAUSTED
}
