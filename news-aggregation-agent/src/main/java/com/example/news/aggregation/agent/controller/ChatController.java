package com.example.news.aggregation.agent.controller;

import com.example.news.aggregation.agent.domain.AgentResponse;
import com.example.news.aggregation.agent.domain.SessionState;
import com.example.news.aggregation.agent.dto.ChatRequest;
import com.example.news.aggregation.agent.dto.CreateSessionRequest;
import com.example.news.aggregation.agent.dto.SessionResponse;
import com.example.news.aggregation.agent.service.LLMOrchestrator;
import com.example.news.aggregation.agent.service.SessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 对话控制器。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class ChatController {

    private final SessionManager sessionManager;
    private final LLMOrchestrator llmOrchestrator;

    /**
     * 对话入口。
     */
    @PostMapping("/chat")
    public ResponseEntity<AgentResponse> chat(@RequestBody ChatRequest request) {
        log.info("[api-step-01] 收到请求: sessionId={}, turnId={}, query={}",
                request.getSessionId(), request.getTurnId(), truncate(request.getQuery(), 120));
        try {
            if (request.getUserId() == null || request.getUserId().isBlank()) {
                request.setUserId("anonymous");
            }

            AgentResponse response = llmOrchestrator.handleChat(request);
            if ("SESSION_BUSY".equals(response.getErrorCode())) {
                log.warn("[api-step-02] 会话并发冲突: sessionId={}, turnId={}, runningTurnId={}",
                        response.getSessionId(), response.getTurnId(), response.getRunningTurnId());
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }
            if ("IDEMPOTENCY_IN_PROGRESS".equals(response.getErrorCode())) {
                log.info("[api-step-03] 幂等请求处理中: sessionId={}, turnId={}, runningTurnId={}",
                        response.getSessionId(), response.getTurnId(), response.getRunningTurnId());
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[api-step-xx] 对话请求失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(buildErrorResponse(
                    request.getSessionId(),
                    request.getTurnId(),
                    "内部错误: " + e.getMessage()
            ));
        }
    }

    /**
     * 创建会话。
     */
    @PostMapping("/session")
    public ResponseEntity<SessionResponse> createSession(@RequestBody CreateSessionRequest request) {
        String userId = request.getUserId() != null ? request.getUserId() : "anonymous";
        SessionState sessionState = sessionManager.createSession(userId);
        return ResponseEntity.ok(SessionResponse.builder()
                .sessionId(sessionState.getSessionId())
                .userId(sessionState.getUserId())
                .createdAt(sessionState.getCreatedAt())
                .build());
    }

    /**
     * 查询会话状态。
     */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<SessionState> getSession(@PathVariable String sessionId) {
        SessionState sessionState = sessionManager.getSession(sessionId);
        if (sessionState == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(sessionState);
    }

    /**
     * 删除会话。
     */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        sessionManager.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    private AgentResponse buildErrorResponse(String sessionId, String turnId, String errorMessage) {
        return AgentResponse.builder()
                .sessionId(sessionId)
                .turnId(turnId)
                .turnStatus("FAILED")
                .errorCode("INTERNAL_ERROR")
                .answer("抱歉，处理请求时出现错误：" + errorMessage)
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
}
