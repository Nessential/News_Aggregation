package com.example.news.aggregation.agent.workflow.selector;

import lombok.Builder;
import lombok.Value;

/**
 * Decision from circuit breaker for a single candidate.
 */
@Value
@Builder
public class ToolCircuitDecision {

    boolean allowed;
    boolean halfOpenProbe;
    ToolCircuitState state;
    String reasonCode;
}

