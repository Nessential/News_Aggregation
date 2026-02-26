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
 * Chat 鎺у埗鍣ㄣ€? * 璐熻矗瀵硅瘽鍏ュ彛銆佷細璇濈鐞嗕笌鍝嶅簲缁勮銆? */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class ChatController {

    private final SessionManager sessionManager;
    private final LLMOrchestrator llmOrchestrator;

    /**
     * 瀵硅瘽鍏ュ彛锛氭帴鏀剁敤鎴?query 骞惰繑鍥?AgentResponse銆?     */
    @PostMapping("/chat")
    public ResponseEntity<AgentResponse> chat(@RequestBody ChatRequest request) {
        log.info("鎺ユ敹瀵硅瘽璇锋眰: sessionId={}, query={}", request.getSessionId(), request.getQuery());
        try {
            if (request.getUserId() == null || request.getUserId().isBlank()) {
                request.setUserId("anonymous");
            }
            log.info("[entry] 杩涘叆瀵硅瘽鍏ュ彛FLOW|agent|entry|sessionId={}|userId={}|query={}",
                    request.getSessionId(), request.getUserId(), truncate(request.getQuery(), 200));
            // 缁熶竴缂栨帓鍏ュ彛
            AgentResponse response = llmOrchestrator.handleChat(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Chat request failed", e);
            return ResponseEntity.status(500).body(buildErrorResponse(
                    request.getSessionId(),
                    "鍐呴儴閿欒: " + e.getMessage()
            ));
        }
    }

    /**
     * 鍒涘缓鏂颁細璇濄€?     */
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
     * 鏌ヨ浼氳瘽鐘舵€併€?     */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<SessionState> getSession(@PathVariable String sessionId) {
        SessionState sessionState = sessionManager.getSession(sessionId);
        if (sessionState == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(sessionState);
    }

    /**
     * 鍒犻櫎浼氳瘽銆?     */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        sessionManager.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    // ========= Private Methods =========

    /**
     * 鏋勫缓閿欒鍝嶅簲銆?     */
    private AgentResponse buildErrorResponse(String sessionId, String errorMessage) {
        return AgentResponse.builder()
                .sessionId(sessionId)
                .answer("鎶辨瓑锛屽鐞嗘偍鐨勮姹傛椂鍑虹幇閿欒锛? + errorMessage")
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

