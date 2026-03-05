package com.example.news.aggregation.agent.execution.service;

/**
 * Gateway used by recovery flow to verify whether an UNKNOWN side effect
 * was eventually applied by the downstream provider.
 */
public interface EffectQueryGateway {

    EffectQueryResult query(String runId, String stepId, String effectKey, String providerTrace);

    enum EffectQueryResult {
        APPLIED,
        NOT_APPLIED,
        UNKNOWN,
        UNSUPPORTED
    }
}
