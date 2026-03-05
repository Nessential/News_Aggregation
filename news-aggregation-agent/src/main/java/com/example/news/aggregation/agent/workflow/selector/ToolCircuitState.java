package com.example.news.aggregation.agent.workflow.selector;

/**
 * Global circuit state for tool + capability.
 */
public enum ToolCircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN;

    public static ToolCircuitState from(String value) {
        if (value == null || value.isBlank()) {
            return CLOSED;
        }
        try {
            return ToolCircuitState.valueOf(value);
        } catch (Exception ignore) {
            return CLOSED;
        }
    }
}

