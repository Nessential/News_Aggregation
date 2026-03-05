package com.example.news.aggregation.agent.execution.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Default implementation when remote effect query is disabled.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.agent.execution.effect-query",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true)
public class NoopEffectQueryGateway implements EffectQueryGateway {

    @Override
    public EffectQueryResult query(String runId, String stepId, String effectKey, String providerTrace) {
        log.debug("[effect-query] remote effect query disabled, return UNSUPPORTED|runId={} |stepId={} |effectKey={}",
                runId, stepId, effectKey);
        return EffectQueryResult.UNSUPPORTED;
    }
}
