package com.example.news.aggregation.agent.workflow.selector;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Local fixed-size sliding window used as signal source only.
 */
@Component
public class ToolHealthWindow {

    private final ToolSelectorProperties properties;
    private final Map<String, WindowBucket> buckets = new ConcurrentHashMap<>();

    public ToolHealthWindow(ToolSelectorProperties properties) {
        this.properties = properties;
    }

    public void recordSuccess(String tool, String capability, long latencyMs) {
        append(tool, capability, EventType.SUCCESS, latencyMs);
    }

    public void recordFailure(String tool, String capability, ToolFailureCategory category, long latencyMs) {
        EventType eventType = switch (category) {
            case TIMEOUT -> EventType.TIMEOUT;
            case SCHEMA_FAIL -> EventType.SCHEMA_FAIL;
            case QUALITY_FAIL -> EventType.QUALITY_FAIL;
            case INFRA_FAIL -> EventType.INFRA_FAIL;
            case OTHER -> EventType.OTHER_FAIL;
        };
        append(tool, capability, eventType, latencyMs);
    }

    public ToolHealthSnapshot snapshot(String tool, String capability) {
        String key = key(tool, capability);
        WindowBucket bucket = buckets.get(key);
        if (bucket == null) {
            return ToolHealthSnapshot.empty();
        }
        bucket.lock.lock();
        try {
            long sample = bucket.events.size();
            if (sample <= 0) {
                return ToolHealthSnapshot.empty();
            }
            long success = 0;
            long infra = 0;
            long timeout = 0;
            long schema = 0;
            long quality = 0;
            long other = 0;
            long latencyTotal = 0;
            long maxLatency = 0;
            for (Event event : bucket.events) {
                switch (event.type) {
                    case SUCCESS -> success++;
                    case INFRA_FAIL -> infra++;
                    case TIMEOUT -> timeout++;
                    case SCHEMA_FAIL -> schema++;
                    case QUALITY_FAIL -> quality++;
                    case OTHER_FAIL -> other++;
                }
                latencyTotal += event.latencyMs;
                if (event.latencyMs > maxLatency) {
                    maxLatency = event.latencyMs;
                }
            }
            return ToolHealthSnapshot.builder()
                    .sampleCount(sample)
                    .successCount(success)
                    .infraFailCount(infra)
                    .timeoutCount(timeout)
                    .schemaFailCount(schema)
                    .qualityFailCount(quality)
                    .otherFailCount(other)
                    .successRate(ratio(success, sample))
                    .infraFailRate(ratio(infra, sample))
                    .timeoutRate(ratio(timeout, sample))
                    .schemaFailRate(ratio(schema, sample))
                    .qualityFailRate(ratio(quality, sample))
                    .avgLatencyMs(sample <= 0 ? 0 : latencyTotal / sample)
                    .maxLatencyMs(maxLatency)
                    .build();
        } finally {
            bucket.lock.unlock();
        }
    }

    private void append(String tool, String capability, EventType type, long latencyMs) {
        String key = key(tool, capability);
        WindowBucket bucket = buckets.computeIfAbsent(key, ignored -> new WindowBucket());
        bucket.lock.lock();
        try {
            bucket.events.addLast(new Event(type, Math.max(latencyMs, 0)));
            int windowSize = Math.max(1, properties.getHealthWindowSize());
            while (bucket.events.size() > windowSize) {
                bucket.events.pollFirst();
            }
        } finally {
            bucket.lock.unlock();
        }
    }

    private String key(String tool, String capability) {
        return (tool == null ? "" : tool) + "|" + (capability == null ? "" : capability);
    }

    private double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return (double) numerator / (double) denominator;
    }

    private enum EventType {
        SUCCESS,
        INFRA_FAIL,
        TIMEOUT,
        SCHEMA_FAIL,
        QUALITY_FAIL,
        OTHER_FAIL
    }

    private record Event(EventType type, long latencyMs) {
    }

    private static final class WindowBucket {
        private final ReentrantLock lock = new ReentrantLock();
        private final Deque<Event> events = new ArrayDeque<>();
    }
}

