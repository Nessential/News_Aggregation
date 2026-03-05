package com.example.news.aggregation.agent.workflow.selector;

import lombok.Builder;
import lombok.Value;

/**
 * Snapshot of local in-memory health window.
 */
@Value
@Builder
public class ToolHealthSnapshot {

    long sampleCount;
    long successCount;
    long infraFailCount;
    long timeoutCount;
    long schemaFailCount;
    long qualityFailCount;
    long otherFailCount;
    double successRate;
    double infraFailRate;
    double timeoutRate;
    double schemaFailRate;
    double qualityFailRate;
    long avgLatencyMs;
    long maxLatencyMs;

    public static ToolHealthSnapshot empty() {
        return ToolHealthSnapshot.builder()
                .sampleCount(0)
                .successCount(0)
                .infraFailCount(0)
                .timeoutCount(0)
                .schemaFailCount(0)
                .qualityFailCount(0)
                .otherFailCount(0)
                .successRate(1.0)
                .infraFailRate(0.0)
                .timeoutRate(0.0)
                .schemaFailRate(0.0)
                .qualityFailRate(0.0)
                .avgLatencyMs(0)
                .maxLatencyMs(0)
                .build();
    }
}

