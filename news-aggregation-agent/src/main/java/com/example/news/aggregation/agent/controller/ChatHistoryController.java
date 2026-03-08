package com.example.news.aggregation.agent.controller;

import com.example.news.aggregation.agent.domain.chat.ChatMessageEntity;
import com.example.news.aggregation.agent.service.ChatHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话历史相关接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/history")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;

    /**
     * 获取指定会话的对话历史。
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> getHistory(
            @PathVariable String sessionId,
            @RequestParam(required = false) String turnId,
            @RequestParam(defaultValue = "50") int limit) {

        List<ChatMessageEntity> messages;
        if (turnId != null && !turnId.isBlank()) {
            messages = chatHistoryService.getTurnMessages(sessionId, turnId);
        } else {
            messages = chatHistoryService.getSessionHistory(sessionId, 1, limit);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("turnId", turnId);
        result.put("messages", messages);
        result.put("count", messages.size());

        return ResponseEntity.ok(result);
    }

    /**
     * 获取用于 LLM 上下文的对话历史。
     */
    @GetMapping("/llm-context/{sessionId}")
    public ResponseEntity<Map<String, Object>> getLlmContext(
            @PathVariable String sessionId,
            @RequestParam(required = false) String turnId,
            @RequestParam(defaultValue = "5") int maxTurns) {

        List<String> contextList = chatHistoryService.getLlmContext(sessionId, turnId, maxTurns);

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("turnId", turnId);
        result.put("contextList", contextList);
        result.put("count", contextList.size());

        return ResponseEntity.ok(result);
    }

    /**
     * 按用户ID获取对话历史。
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<Map<String, Object>> getUserHistory(
            @PathVariable String userId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "50") int pageSize) {

        List<ChatMessageEntity> messages = chatHistoryService.getUserHistory(userId, pageNum, pageSize);

        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        result.put("messages", messages);
        result.put("count", messages.size());

        return ResponseEntity.ok(result);
    }
}
