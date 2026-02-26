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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * Chat 控制器。
 * 负责对话入口、会话管理与响应组装。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class ChatController {

    private final SessionManager sessionManager;
    private final LLMOrchestrator llmOrchestrator;

    /**
     * 对话入口：接收用户 query 并返回 AgentResponse。
     */
    @PostMapping("/chat")
    public ResponseEntity<AgentResponse> chat(@RequestBody ChatRequest request) {
        log.info("接收对话请求: sessionId={}, query={}", request.getSessionId(), request.getQuery());
        try {
            if (request.getUserId() == null || request.getUserId().isBlank()) {
                request.setUserId("anonymous");
            }
            log.info("进入对话入口FLOW|agent|entry|sessionId={}|userId={}|query={}",
                    request.getSessionId(), request.getUserId(), truncate(request.getQuery(), 200));
            // 统一编排入口
            AgentResponse response = llmOrchestrator.handleChat(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Chat request failed", e);
            return ResponseEntity.status(500).body(buildErrorResponse(
                    request.getSessionId(),
                    "内部错误: " + e.getMessage()
            ));
        }
    }

    /**
     * 创建新会话。
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

    // ========= Private Methods =========

    /**
     * 构建错误响应。
     */
    private AgentResponse buildErrorResponse(String sessionId, String errorMessage) {
        return AgentResponse.builder()
                .sessionId(sessionId)
                .answer("抱歉，处理您的请求时出现错误：" + errorMessage)
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
