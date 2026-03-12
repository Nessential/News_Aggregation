package com.example.news.aggregation.agent.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Request-scoped retrieval metrics holder with cross-thread trace binding.
 */
public final class RetrievalMetricsContext {

    private static final ThreadLocal<String> TRACE_ID_HOLDER = new ThreadLocal<>();
    private static final ConcurrentMap<String, Metrics> STORE = new ConcurrentHashMap<>();

    private RetrievalMetricsContext() {
    }

    public static void startRequest(String traceId) {
        String normalized = normalizeTraceId(traceId);
        TRACE_ID_HOLDER.set(normalized);
        STORE.put(normalized, new Metrics());
    }

    public static void bindTrace(String traceId) {
        TRACE_ID_HOLDER.set(normalizeTraceId(traceId));
    }

    public static void unbindTrace() {
        TRACE_ID_HOLDER.remove();
    }

    public static int recordEs(long elapsedMs) {
        Metrics metrics = ensure();
        metrics.esCount++;
        metrics.esTotalMs += Math.max(elapsedMs, 0L);
        metrics.retrievalTotalMs += Math.max(elapsedMs, 0L);
        return metrics.esCount;
    }

    public static int recordVector(long elapsedMs) {
        Metrics metrics = ensure();
        metrics.vectorCount++;
        metrics.vectorTotalMs += Math.max(elapsedMs, 0L);
        metrics.retrievalTotalMs += Math.max(elapsedMs, 0L);
        return metrics.vectorCount;
    }

    public static int recordHybrid(long elapsedMs) {
        Metrics metrics = ensure();
        metrics.hybridCount++;
        metrics.hybridTotalMs += Math.max(elapsedMs, 0L);
        metrics.retrievalTotalMs += Math.max(elapsedMs, 0L);
        return metrics.hybridCount;
    }

    public static Snapshot snapshot(String traceId) {
        Metrics metrics = STORE.get(normalizeTraceId(traceId));
        if (metrics == null) {
            return Snapshot.zero();
        }
        return new Snapshot(
                metrics.esCount,
                metrics.vectorCount,
                metrics.hybridCount,
                metrics.esTotalMs,
                metrics.vectorTotalMs,
                metrics.hybridTotalMs,
                metrics.retrievalTotalMs
        );
    }

    public static void clear(String traceId) {
        String normalized = normalizeTraceId(traceId);
        STORE.remove(normalized);
        String boundTraceId = TRACE_ID_HOLDER.get();
        if (normalized.equals(boundTraceId)) {
            TRACE_ID_HOLDER.remove();
        }
    }

    private static Metrics ensure() {
        String traceId = TRACE_ID_HOLDER.get();
        if (traceId == null || traceId.isBlank()) {
            traceId = "THREAD-" + Thread.currentThread().getId();
            TRACE_ID_HOLDER.set(traceId);
        }
        return STORE.computeIfAbsent(traceId, ignored -> new Metrics());
    }

    private static String normalizeTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return "UNKNOWN";
        }
        return traceId.trim();
    }

    private static final class Metrics {
        private int esCount;
        private int vectorCount;
        private int hybridCount;
        private long esTotalMs;
        private long vectorTotalMs;
        private long hybridTotalMs;
        private long retrievalTotalMs;
    }

    public record Snapshot(
            int esCount,
            int vectorCount,
            int hybridCount,
            long esTotalMs,
            long vectorTotalMs,
            long hybridTotalMs,
            long retrievalTotalMs
    ) {
        public static Snapshot zero() {
            return new Snapshot(0, 0, 0, 0L, 0L, 0L, 0L);
        }
    }
}
