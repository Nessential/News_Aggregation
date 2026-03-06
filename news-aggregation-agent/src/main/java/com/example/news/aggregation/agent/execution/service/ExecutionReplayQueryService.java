package com.example.news.aggregation.agent.execution.service;

import com.example.news.aggregation.agent.dto.ExecutionEventPageResponse;
import com.example.news.aggregation.agent.dto.ExecutionReplayResponse;
import com.example.news.aggregation.agent.execution.config.ExecutionReplayProperties;
import com.example.news.aggregation.agent.execution.domain.ExecutionEventLogEntity;
import com.example.news.aggregation.agent.execution.domain.ExecutionRunEntity;
import com.example.news.aggregation.agent.execution.domain.ExecutionStepRunEntity;
import com.example.news.aggregation.agent.execution.model.ExecutionReplaySnapshot;
import com.example.news.aggregation.agent.execution.repo.ExecutionEventLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Week6 回放查询服务：
 * 1. 统一租户+会话鉴权；
 * 2. 统一 payload 脱敏；
 * 3. 提供 replay 和 events 两类只读接口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionReplayQueryService {

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "token", "access_token", "refresh_token", "secret", "password",
            "api_key", "apikey", "authorization", "cookie", "set_cookie", "set-cookie"
    );

    private final ExecutionRunService executionRunService;
    private final ExecutionEventLogRepository eventLogRepository;
    private final ExecutionReplayProperties replayProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExecutionReplayResponse buildReplay(String runId,
                                               String requesterTenantId,
                                               String requesterSessionId,
                                               boolean includeRawPayload) {
        validateRunId(runId);
        ensureReplayFeatureEnabled();
        ExecutionReplaySnapshot snapshot = executionRunService.buildReplaySnapshot(
                runId,
                normalizeLimit(replayProperties.getDefaultEventLimit())
        );
        if (snapshot == null || snapshot.getRun() == null) {
            return null;
        }
        ensureRequesterAuthorized(snapshot.getRun(), requesterTenantId, requesterSessionId);
        return ExecutionReplayResponse.builder()
                .run(toRunView(snapshot.getRun()))
                .stepRuns(toStepViews(snapshot.getStepRuns()))
                .events(toEventViews(snapshot.getEvents(), includeRawPayload, runId))
                .summary(ExecutionReplayResponse.SummaryView.builder()
                        .activePlanVersion(snapshot.getActivePlanVersion())
                        .stepCount(snapshot.getStepCount())
                        .eventCount(snapshot.getEventCount())
                        .terminalState(snapshot.getTerminalState())
                        .timelineDigest(snapshot.getTimelineDigest())
                        .plannerTraceId(snapshot.getPlannerTraceId())
                        .build())
                .build();
    }

    public ExecutionEventPageResponse queryEvents(String runId,
                                                  String requesterTenantId,
                                                  String requesterSessionId,
                                                  Long cursor,
                                                  Integer limit,
                                                  boolean includeRawPayload) {
        validateRunId(runId);
        ensureReplayFeatureEnabled();
        long safeCursor = cursor == null ? 0L : Math.max(0L, cursor);
        int safeLimit = normalizeLimit(limit);
        ExecutionRunEntity run = executionRunService.findByRunId(runId);
        if (run == null) {
            return null;
        }
        ensureRequesterAuthorized(run, requesterTenantId, requesterSessionId);
        List<ExecutionEventLogEntity> queried = eventLogRepository.listByRunIdAfterEventId(
                runId,
                safeCursor,
                safeLimit + 1
        );
        boolean hasMore = queried != null && queried.size() > safeLimit;
        List<ExecutionEventLogEntity> currentPage;
        if (queried == null || queried.isEmpty()) {
            currentPage = List.of();
        } else if (hasMore) {
            currentPage = new ArrayList<>(queried.subList(0, safeLimit));
        } else {
            currentPage = queried;
        }
        Long nextCursor = null;
        if (hasMore && !currentPage.isEmpty()) {
            nextCursor = currentPage.get(currentPage.size() - 1).getId();
        }
        log.info("[execution-replay] 事件分页查询完成|runId={} |cursor={} |limit={} |hasMore={} |nextCursor={} |count={}",
                runId, safeCursor, safeLimit, hasMore, nextCursor, currentPage.size());
        return ExecutionEventPageResponse.builder()
                .runId(runId)
                .cursor(safeCursor)
                .limit(safeLimit)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .events(toEventViews(currentPage, includeRawPayload, runId))
                .build();
    }

    public boolean isReplayApiEnabled() {
        return replayProperties.isApiEnabled();
    }

    private void ensureReplayFeatureEnabled() {
        if (!replayProperties.isApiEnabled()) {
            log.warn("[execution-replay] 回放能力已关闭|reasonCode=feature_disabled_replay");
            throw new IllegalStateException("feature_disabled_replay");
        }
    }

    private void validateRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("run_id_required");
        }
    }

    private void ensureRequesterAuthorized(ExecutionRunEntity run,
                                           String requesterTenantId,
                                           String requesterSessionId) {
        String expectedSessionId = run == null ? null : safeTrim(run.getSessionId());
        String normalizedRequesterSession = safeTrim(requesterSessionId);
        if (normalizedRequesterSession == null || normalizedRequesterSession.isBlank()) {
            log.warn("[execution-replay] 会话鉴权失败：缺少 sessionId|runId={} |reasonCode=replay_session_missing",
                    run == null ? null : run.getRunId());
            throw new SecurityException("replay_session_missing");
        }
        if (expectedSessionId == null || expectedSessionId.isBlank() || !expectedSessionId.equals(normalizedRequesterSession)) {
            log.warn("[execution-replay] 会话鉴权失败：session 不匹配|runId={} |expectedSessionId={} |requestSessionId={} |reasonCode=replay_session_forbidden",
                    run == null ? null : run.getRunId(), expectedSessionId, normalizedRequesterSession);
            throw new SecurityException("replay_session_forbidden");
        }

        String expectedTenantId = normalizeTenant(run == null ? null : run.getTenantId());
        String normalizedRequesterTenant = normalizeTenant(requesterTenantId);
        if (!expectedTenantId.equals(normalizedRequesterTenant)) {
            log.warn("[execution-replay] 租户鉴权失败：tenant 不匹配|runId={} |expectedTenantId={} |requestTenantId={} |reasonCode=replay_tenant_forbidden",
                    run == null ? null : run.getRunId(), expectedTenantId, normalizedRequesterTenant);
            throw new SecurityException("replay_tenant_forbidden");
        }
    }

    private int normalizeLimit(Integer requested) {
        int defaultLimit = Math.max(1, replayProperties.getDefaultEventLimit());
        int maxLimit = Math.max(defaultLimit, replayProperties.getMaxEventLimit());
        int resolved = requested == null ? defaultLimit : requested;
        if (resolved <= 0) {
            return defaultLimit;
        }
        return Math.min(resolved, maxLimit);
    }

    private ExecutionReplayResponse.RunView toRunView(ExecutionRunEntity run) {
        return ExecutionReplayResponse.RunView.builder()
                .runId(run.getRunId())
                .tenantId(normalizeTenant(run.getTenantId()))
                .sessionId(run.getSessionId())
                .turnId(run.getTurnId())
                .status(run.getStatus())
                .currentStep(run.getCurrentStep())
                .errorCode(run.getErrorCode())
                .errorMessage(run.getErrorMessage())
                .activePlanVersion(run.getActivePlanVersion())
                .replanCountRun(run.getReplanCountRun())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .build();
    }

    private List<ExecutionReplayResponse.StepRunView> toStepViews(List<ExecutionStepRunEntity> stepRuns) {
        if (stepRuns == null || stepRuns.isEmpty()) {
            return List.of();
        }
        List<ExecutionReplayResponse.StepRunView> views = new ArrayList<>(stepRuns.size());
        for (ExecutionStepRunEntity item : stepRuns) {
            if (item == null) {
                continue;
            }
            views.add(ExecutionReplayResponse.StepRunView.builder()
                    .id(item.getId())
                    .stepId(item.getStepId())
                    .planVersion(item.getPlanVersion())
                    .capabilityName(item.getCapabilityName())
                    .activeCapabilityName(item.getActiveCapabilityName())
                    .status(item.getStatus())
                    .attempt(item.getAttempt())
                    .maxRetries(item.getMaxRetries())
                    .recoveryAttempt(item.getRecoveryAttempt())
                    .maxRecoveryAttempts(item.getMaxRecoveryAttempts())
                    .selectedTool(item.getSelectedTool())
                    .selectionReasonCode(item.getSelectionReasonCode())
                    .replanCountStep(item.getReplanCountStep())
                    .lastReplanReasonCode(item.getLastReplanReasonCode())
                    .replanDecisionAction(item.getReplanDecisionAction())
                    .reasonCode(item.getReasonCode())
                    .errorCode(item.getErrorCode())
                    .errorMessage(item.getErrorMessage())
                    .startedAt(item.getStartedAt())
                    .finishedAt(item.getFinishedAt())
                    .build());
        }
        return views;
    }

    private List<ExecutionReplayResponse.EventView> toEventViews(List<ExecutionEventLogEntity> events,
                                                                 boolean includeRawPayload,
                                                                 String runId) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<ExecutionReplayResponse.EventView> views = new ArrayList<>(events.size());
        for (ExecutionEventLogEntity event : events) {
            if (event == null) {
                continue;
            }
            views.add(ExecutionReplayResponse.EventView.builder()
                    .id(event.getId())
                    .stepId(event.getStepId())
                    .eventType(event.getEventType())
                    .eventVersion(event.getEventVersion())
                    .fromState(event.getFromState())
                    .toState(event.getToState())
                    .reasonCode(event.getReasonCode())
                    .message(event.getMessage())
                    .payloadJson(sanitizePayload(event.getPayloadJson(), includeRawPayload, runId))
                    .gmtCreate(event.getGmtCreate())
                    .build());
        }
        return views;
    }

    private String sanitizePayload(String payloadJson, boolean includeRawPayload, String runId) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return payloadJson;
        }
        if (includeRawPayload && replayProperties.isRawPayloadEnabled()) {
            log.warn("[execution-replay] 返回原始 payload（仅内部开关允许）|runId={} |audit=true", runId);
            return truncateIfNeeded(payloadJson);
        }
        if (includeRawPayload) {
            log.warn("[execution-replay] 请求原始 payload 被拒绝，已返回脱敏版|runId={} |reasonCode=replay_raw_payload_disabled", runId);
        }
        try {
            JsonNode parsed = objectMapper.readTree(payloadJson);
            JsonNode sanitized = sanitizeJsonNode(parsed, null);
            return truncateIfNeeded(objectMapper.writeValueAsString(sanitized));
        } catch (Exception ignore) {
            return sanitizePlainText(payloadJson);
        }
    }

    private JsonNode sanitizeJsonNode(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (isSensitiveKey(fieldName)) {
            return JsonNodeFactory.instance.textNode(maskSensitiveValue(node.asText(node.toString())));
        }
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(entry ->
                    out.set(entry.getKey(), sanitizeJsonNode(entry.getValue(), entry.getKey())));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode child : node) {
                out.add(sanitizeJsonNode(child, fieldName));
            }
            return out;
        }
        if (node.isTextual()) {
            return JsonNodeFactory.instance.textNode(truncateIfNeeded(node.asText()));
        }
        return node;
    }

    private String sanitizePlainText(String plainText) {
        String masked = plainText;
        for (String key : SENSITIVE_KEYS) {
            masked = masked.replaceAll("(?i)(" + key + "\\s*[:=]\\s*)([^,;\\s]+)", "$1***");
        }
        return truncateIfNeeded(masked);
    }

    private boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (SENSITIVE_KEYS.contains(normalized)) {
            return true;
        }
        return normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("authorization")
                || normalized.contains("cookie")
                || normalized.contains("api_key")
                || normalized.contains("apikey");
    }

    private String truncateIfNeeded(String value) {
        if (value == null) {
            return null;
        }
        int maxLength = Math.max(64, replayProperties.getPayloadMaskMaxLength());
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...(truncated)";
    }

    private String maskSensitiveValue(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return "***";
        }
        return "hash:" + sha256(rawValue).substring(0, 12);
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return "0000000000000000";
        }
    }

    private String normalizeTenant(String tenantId) {
        String normalized = safeTrim(tenantId);
        if (normalized == null || normalized.isBlank()) {
            String defaultTenant = safeTrim(replayProperties.getDefaultTenantId());
            return (defaultTenant == null || defaultTenant.isBlank()) ? "default" : defaultTenant;
        }
        return normalized;
    }

    private String safeTrim(String text) {
        return text == null ? null : text.trim();
    }
}
