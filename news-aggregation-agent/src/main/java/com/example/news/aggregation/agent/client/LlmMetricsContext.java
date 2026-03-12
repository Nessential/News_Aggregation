package com.example.news.aggregation.agent.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Request-scoped LLM metrics holder with cross-thread trace binding.
 */
public final class LlmMetricsContext {

    private static final ThreadLocal<String> TRACE_ID_HOLDER = new ThreadLocal<>();
    private static final ConcurrentMap<String, Metrics> STORE = new ConcurrentHashMap<>();

    private LlmMetricsContext() {
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

    public static CallIndex recordIntent(long elapsedMs) {
        Metrics metrics = ensure();
        metrics.callCount++;
        metrics.intentCount++;
        metrics.intentTotalMs += Math.max(elapsedMs, 0L);
        metrics.totalMs += Math.max(elapsedMs, 0L);
        return new CallIndex(metrics.callCount, metrics.intentCount);
    }

    public static CallIndex recordPlan(long elapsedMs) {
        Metrics metrics = ensure();
        metrics.callCount++;
        metrics.planCount++;
        metrics.planTotalMs += Math.max(elapsedMs, 0L);
        metrics.totalMs += Math.max(elapsedMs, 0L);
        return new CallIndex(metrics.callCount, metrics.planCount);
    }

    public static CallIndex recordAnswer(long elapsedMs) {
        Metrics metrics = ensure();
        metrics.callCount++;
        metrics.answerCount++;
        metrics.answerTotalMs += Math.max(elapsedMs, 0L);
        metrics.totalMs += Math.max(elapsedMs, 0L);
        return new CallIndex(metrics.callCount, metrics.answerCount);
    }

    public static Snapshot snapshot(String traceId) {
        Metrics metrics = STORE.get(normalizeTraceId(traceId));
        if (metrics == null) {
            return Snapshot.zero();
        }
        return new Snapshot(
                metrics.callCount,
                metrics.intentCount,
                metrics.planCount,
                metrics.answerCount,
                metrics.intentTotalMs,
                metrics.planTotalMs,
                metrics.answerTotalMs,
                metrics.totalMs
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
        private int callCount;
        private int intentCount;
        private int planCount;
        private int answerCount;
        private long intentTotalMs;
        private long planTotalMs;
        private long answerTotalMs;
        private long totalMs;
    }

    public record CallIndex(int callNo, int phaseCallNo) {
    }

    public record Snapshot(
            int callCount,
            int intentCount,
            int planCount,
            int answerCount,
            long intentTotalMs,
            long planTotalMs,
            long answerTotalMs,
            long totalMs
    ) {
        public static Snapshot zero() {
            return new Snapshot(0, 0, 0, 0, 0L, 0L, 0L, 0L);
        }
    }
}
