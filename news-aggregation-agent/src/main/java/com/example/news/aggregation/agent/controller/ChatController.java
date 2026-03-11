package com.example.news.aggregation.agent.controller;

import com.example.news.aggregation.agent.domain.AgentResponse;
import com.example.news.aggregation.agent.domain.SessionState;
import com.example.news.aggregation.agent.dto.ChatRequest;
import com.example.news.aggregation.agent.dto.CreateSessionRequest;
import com.example.news.aggregation.agent.dto.ExecutionResumeRequest;
import com.example.news.aggregation.agent.dto.SessionResponse;
import com.example.news.aggregation.agent.execution.domain.ExecutionRunEntity;
import com.example.news.aggregation.agent.execution.service.ExecutionDispatchService;
import com.example.news.aggregation.agent.execution.service.ExecutionResumeService;
import com.example.news.aggregation.agent.execution.service.ExecutionRunService;
import com.example.news.aggregation.agent.security.UserContextHolder;
import com.example.news.aggregation.agent.service.ChatHistoryService;
import com.example.news.aggregation.agent.service.LLMOrchestrator;
import com.example.news.aggregation.agent.service.SessionManager;
import com.example.news.aggregation.cache.quota.model.FeatureQuotaCheckResult;
import com.example.news.aggregation.cache.quota.model.FeatureQuotaConsumeResult;
import com.example.news.aggregation.cache.quota.service.FeatureQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class ChatController {

    private static final String FEATURE_CHAT = "chat";

    private final SessionManager sessionManager;
    private final ChatHistoryService chatHistoryService;
    private final LLMOrchestrator llmOrchestrator;
    private final ExecutionResumeService executionResumeService;
    private final ExecutionRunService executionRunService;
    private final ExecutionDispatchService executionDispatchService;
    private final FeatureQuotaService featureQuotaService;

    @PostMapping("/chat")
    public ResponseEntity<AgentResponse> chat(@RequestBody ChatRequest request) {
        log.info("[api-chat] \u6536\u5230\u804a\u5929\u8bf7\u6c42|sessionId={} |turnId={} |query={}",
                request.getSessionId(), request.getTurnId(), truncate(request.getQuery(), 120));
        try {
            String currentUserId = requireCurrentUserId();
            if (currentUserId == null) {
                log.warn("[api-chat] \u672a\u767b\u5f55\u8bbf\u95ee\u88ab\u62d2\u7edd|sessionId={} |turnId={} |reason=missing_user_context",
                        request.getSessionId(), request.getTurnId());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(buildErrorResponse(
                        request.getSessionId(),
                        request.getTurnId(),
                        "Authentication required",
                        "UNAUTHORIZED"
                ));
            }

            if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
                SessionState existing = sessionManager.getSession(request.getSessionId());
                if (existing == null) {
                    if (!chatHistoryService.sessionBelongsToUser(request.getSessionId(), currentUserId)) {
                        log.warn("[api-chat] 会话不存在且无历史归属，拒绝访问|sessionId={} |turnId={} |userId={}",
                                request.getSessionId(), request.getTurnId(), currentUserId);
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(buildErrorResponse(
                                request.getSessionId(),
                                request.getTurnId(),
                                "Session not found",
                                "SESSION_NOT_FOUND"
                        ));
                    }
                    // Redis 会话过期但历史存在时，先恢复会话再继续聊天。
                    List<String> context = chatHistoryService.getLlmContext(request.getSessionId(), null, 5);
                    sessionManager.restoreSession(request.getSessionId(), currentUserId, context);
                    log.info("[api-chat] 检测到会话已过期，已按历史数据恢复|sessionId={} |userId={} |historySize={}",
                            request.getSessionId(), currentUserId, context.size());
                    existing = sessionManager.getSession(request.getSessionId());
                    if (existing == null) {
                        log.warn("[api-chat] 会话恢复失败，拒绝访问|sessionId={} |turnId={} |userId={}",
                                request.getSessionId(), request.getTurnId(), currentUserId);
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(buildErrorResponse(
                                request.getSessionId(),
                                request.getTurnId(),
                                "Session not found",
                                "SESSION_NOT_FOUND"
                        ));
                    }
                }
                if (!currentUserId.equals(existing.getUserId())) {
                    log.warn("[api-chat] \u4f1a\u8bdd\u8d8a\u6743\u8bbf\u95ee|sessionId={} |turnId={} |ownerUserId={} |requestUserId={}",
                            request.getSessionId(), request.getTurnId(), existing.getUserId(), currentUserId);
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(buildErrorResponse(
                            request.getSessionId(),
                            request.getTurnId(),
                            "Session does not belong to current user",
                            "SESSION_FORBIDDEN"
                    ));
                }
            }

            request.setUserId(currentUserId);
            Long currentUserIdLong = parseUserId(currentUserId);
            if (currentUserIdLong == null) {
                log.warn("[api-chat] \u7528\u6237ID\u683c\u5f0f\u975e\u6cd5\uff0c\u62d2\u7edd\u8bf7\u6c42|sessionId={} |turnId={} |userId={}",
                        request.getSessionId(), request.getTurnId(), currentUserId);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(buildErrorResponse(
                        request.getSessionId(),
                        request.getTurnId(),
                        "Authentication required",
                        "UNAUTHORIZED"
                ));
            }

            FeatureQuotaCheckResult quotaCheckResult = null;
            try {
                quotaCheckResult = featureQuotaService.checkQuota(currentUserIdLong, FEATURE_CHAT);
                if (!quotaCheckResult.isAllowed()) {
                    log.warn("[quota] \u804a\u5929\u989d\u5ea6\u4e0d\u8db3\uff0c\u62d2\u7edd\u8bf7\u6c42|userId={} |sessionId={} |turnId={} |reasonCode={}",
                            currentUserIdLong, request.getSessionId(), request.getTurnId(), quotaCheckResult.getReasonCode());
                    AgentResponse exceedResponse = buildErrorResponse(
                            request.getSessionId(),
                            request.getTurnId(),
                            quotaCheckResult.getReasonMessage() == null ? "\u4eca\u65e5\u804a\u5929\u989d\u5ea6\u5df2\u7528\u5c3d" : quotaCheckResult.getReasonMessage(),
                            "FEATURE_QUOTA_EXCEEDED"
                    );
                    exceedResponse.setFeatureQuotas(quotaCheckResult.getAllQuotas());
                    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(exceedResponse);
                }
            } catch (Exception ex) {
                // Quota component exception should not break the main flow.
                log.error("[quota] \u804a\u5929\u989d\u5ea6\u68c0\u67e5\u5f02\u5e38\uff0c\u5ffd\u7565\u5e76\u7ee7\u7eed\u4e3b\u6d41\u7a0b|userId={} |sessionId={} |turnId={}",
                        currentUserIdLong, request.getSessionId(), request.getTurnId(), ex);
            }

            AgentResponse response = llmOrchestrator.handleChat(request);
            if (response != null && quotaCheckResult != null && response.getFeatureQuotas() == null) {
                response.setFeatureQuotas(quotaCheckResult.getAllQuotas());
            }

            if (response != null && shouldConsumeQuota(response)) {
                try {
                    FeatureQuotaConsumeResult consumeResult = featureQuotaService.consumeQuota(currentUserIdLong, FEATURE_CHAT);
                    response.setFeatureQuotas(consumeResult.getAllQuotas());
                    log.info("[quota] \u804a\u5929\u6210\u529f\u540e\u6263\u51cf\u989d\u5ea6\u5b8c\u6210|userId={} |sessionId={} |turnId={} |reasonCode={}",
                            currentUserIdLong, request.getSessionId(), request.getTurnId(), consumeResult.getReasonCode());
                } catch (Exception ex) {
                    // Quota component exception should not break the main flow.
                    log.error("[quota] \u804a\u5929\u6210\u529f\u540e\u6263\u51cf\u989d\u5ea6\u5f02\u5e38\uff0c\u5ffd\u7565\u5e76\u7ee7\u7eed\u8fd4\u56de|userId={} |sessionId={} |turnId={}",
                            currentUserIdLong, request.getSessionId(), request.getTurnId(), ex);
                }
            }

            if ("SESSION_BUSY".equals(response.getErrorCode())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }
            if ("IDEMPOTENCY_IN_PROGRESS".equals(response.getErrorCode())) {
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[api-chat] \u804a\u5929\u8bf7\u6c42\u5904\u7406\u5931\u8d25", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(buildErrorResponse(
                    request.getSessionId(),
                    request.getTurnId(),
                    "Internal error: " + e.getMessage(),
                    "INTERNAL_ERROR"
            ));
        }
    }

    @PostMapping("/execution/run/{runId}/resume")
    public ResponseEntity<Map<String, Object>> resumeExecution(@PathVariable String runId,
                                                               @RequestBody(required = false) ExecutionResumeRequest request) {
        ExecutionRunEntity run = executionRunService.findByRunId(runId);
        if (run == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "errorCode", "RUN_NOT_FOUND", "runId", runId));
        }

        String stepId = request != null && request.getStepId() != null && !request.getStepId().isBlank()
                ? request.getStepId()
                : run.getCurrentStep();
        if (stepId == null || stepId.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "errorCode", "STEP_ID_REQUIRED", "runId", runId));
        }

        Map<String, Object> resumeInput = request == null ? null : request.getResumeInput();
        boolean resumed = executionResumeService.resumeWaitingStep(runId, stepId, resumeInput);
        if (!resumed) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("success", false, "errorCode", "RESUME_CONFLICT", "runId", runId, "stepId", stepId));
        }

        boolean dispatched = executionDispatchService.dispatchRun(runId, stepId, resumeInput);
        if (!dispatched) {
            executionResumeService.rollbackPendingToWaiting(
                    runId,
                    stepId,
                    "resume_dispatch_failed",
                    "resume dispatch failed"
            );
            executionRunService.markWaiting(runId, stepId, "resume_dispatch_failed", "resume dispatch failed");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "errorCode", "RESUME_DISPATCH_FAILED", "runId", runId, "stepId", stepId));
        }

        ExecutionRunEntity latest = executionRunService.findByRunId(runId);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("runId", runId);
        body.put("stepId", stepId);
        body.put("status", latest == null ? "RUNNING" : latest.getStatus());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/session")
    public ResponseEntity<SessionResponse> createSession(@RequestBody CreateSessionRequest request) {
        String userId = requireCurrentUserId();
        if (userId == null) {
            log.warn("[api-session-create] \u672a\u767b\u5f55\u8bbf\u95ee\u88ab\u62d2\u7edd|reason=missing_user_context");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        SessionState sessionState = sessionManager.createSession(userId);
        return ResponseEntity.ok(SessionResponse.builder()
                .sessionId(sessionState.getSessionId())
                .userId(sessionState.getUserId())
                .createdAt(sessionState.getCreatedAt())
                .build());
    }

    @GetMapping("/session/{sessionId}")
    public ResponseEntity<SessionState> getSession(@PathVariable String sessionId) {
        String userId = requireCurrentUserId();
        if (userId == null) {
            log.warn("[api-session-get] \u672a\u767b\u5f55\u8bbf\u95ee\u88ab\u62d2\u7edd|sessionId={} |reason=missing_user_context", sessionId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        SessionState sessionState = sessionManager.getSession(sessionId);
        if (sessionState == null) {
            log.warn("[api-session-get] \u4f1a\u8bdd\u4e0d\u5b58\u5728|sessionId={} |userId={}", sessionId, userId);
            return ResponseEntity.notFound().build();
        }
        if (!userId.equals(sessionState.getUserId())) {
            log.warn("[api-session-get] \u4f1a\u8bdd\u8d8a\u6743\u8bbf\u95ee|sessionId={} |ownerUserId={} |requestUserId={}",
                    sessionId, sessionState.getUserId(), userId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(sessionState);
    }

    @GetMapping("/session/recent")
    public ResponseEntity<List<SessionResponse>> listRecentSessions(
            @RequestParam(value = "limit", required = false, defaultValue = "10") int limit) {
        String userId = requireCurrentUserId();
        if (userId == null) {
            log.warn("[api-session-recent] \u672a\u767b\u5f55\u8bbf\u95ee\u88ab\u62d2\u7edd|reason=missing_user_context");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 10 : limit, 50));
        List<SessionResponse> sessions = sessionManager.listUserSessions(userId, safeLimit).stream()
                .map(session -> SessionResponse.builder()
                        .sessionId(session.getSessionId())
                        .userId(session.getUserId())
                        .createdAt(session.getCreatedAt())
                        .build())
                .toList();
        if (!sessions.isEmpty()) {
            return ResponseEntity.ok(sessions);
        }

        // Redis 会话可能已过期，兜底从聊天消息表聚合最近会话。
        try {
            List<SessionResponse> fallbackSessions = chatHistoryService.getRecentSessionsByUser(userId, safeLimit).stream()
                    .map(summary -> SessionResponse.builder()
                            .sessionId(summary.getSessionId())
                            .userId(summary.getUserId())
                            .createdAt(summary.getLatestAt())
                            .build())
                    .toList();
            log.info("[api-session-recent] Redis无会话，已走数据库兜底|userId={} |count={}", userId, fallbackSessions.size());
            return ResponseEntity.ok(fallbackSessions);
        } catch (Exception ex) {
            log.error("[api-session-recent] 查询最近会话失败（数据库兜底异常）|userId={}", userId, ex);
        }
        return ResponseEntity.ok(sessions);
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        String userId = requireCurrentUserId();
        if (userId == null) {
            log.warn("[api-session-delete] \u672a\u767b\u5f55\u8bbf\u95ee\u88ab\u62d2\u7edd|sessionId={} |reason=missing_user_context", sessionId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        SessionState sessionState = sessionManager.getSession(sessionId);
        if (sessionState == null) {
            log.warn("[api-session-delete] \u4f1a\u8bdd\u4e0d\u5b58\u5728|sessionId={} |userId={}", sessionId, userId);
            return ResponseEntity.notFound().build();
        }
        if (!userId.equals(sessionState.getUserId())) {
            log.warn("[api-session-delete] \u4f1a\u8bdd\u8d8a\u6743\u8bbf\u95ee|sessionId={} |ownerUserId={} |requestUserId={}",
                    sessionId, sessionState.getUserId(), userId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        sessionManager.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    private String requireCurrentUserId() {
        String userId = UserContextHolder.getUserId();
        if (userId == null) {
            return null;
        }
        String trimmed = userId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private AgentResponse buildErrorResponse(String sessionId, String turnId, String errorMessage, String errorCode) {
        return AgentResponse.builder()
                .sessionId(sessionId)
                .turnId(turnId)
                .turnStatus("FAILED")
                .errorCode(errorCode)
                .answer(errorMessage)
                .answerMarkdown(errorMessage == null ? "" : errorMessage)
                .timestamp(LocalDateTime.now())
                .build();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    private Long parseUserId(String userId) {
        try {
            return Long.parseLong(userId);
        } catch (Exception ignore) {
            return null;
        }
    }

    private boolean shouldConsumeQuota(AgentResponse response) {
        if (response == null) {
            return false;
        }
        if (!"DONE".equalsIgnoreCase(response.getTurnStatus())) {
            return false;
        }
        String errorCode = response.getErrorCode();
        return errorCode == null || errorCode.isBlank();
    }
}
