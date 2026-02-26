package com.example.news.aggregation.llm.springai.controller;

import com.example.news.aggregation.llm.springai.contract.ChatRequest;
import com.example.news.aggregation.llm.springai.contract.ChatResponse;
import com.example.news.aggregation.llm.springai.orchestrator.LLMOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 聊天API控制器
 * 提供RESTful接口供外部调用LLM服务
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {
    
    /** LLM编排器，负责执行完整的RAG流程 */
    private final LLMOrchestrator orchestrator;
    
    /**
     * 处理聊天消息
     * 
     * @param request 聊天请求
     * @return 聊天响应，包含答案和来源
     */
    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        log.info("[ChatController] Received chat request: sessionId={}, userId={}", 
                request.getSessionId(), request.getUserId());
        
        // 输入验证
        
        if (request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            throw new IllegalArgumentException("Message cannot be empty");
        }
        if (request.getMessage().length() > 5000) {
            throw new IllegalArgumentException("Message too long (max 5000 characters)");
        }
        
        // 生成会话ID（如果未提供）
        String sessionId = request.getSessionId() != null && !request.getSessionId().isEmpty() 
                ? request.getSessionId() 
                : UUID.randomUUID().toString();
        
        // 生成用户ID（如果未提供，使用guest_前缀）
        String userId = request.getUserId() != null && !request.getUserId().isEmpty() 
                ? request.getUserId() 
                : "guest_" + UUID.randomUUID().toString().substring(0, 8);
        
        return orchestrator.processQuery(sessionId, request.getMessage(), userId);
    }
    
    /**
     * 健康检查接口
     * 
     * @return OK表示服务正常
     */
    @GetMapping("/health")
    public String health() {
        return "OK";
    }
    
    /**
     * 处理IllegalArgumentException（输入验证错误）
     * 返回400 Bad Request
     * 
     * @param ex 非法参数异常
     * @return 错误响应实体
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("[ChatController] Validation error: {}", ex.getMessage());
        
        ErrorResponse error = new ErrorResponse(
            "INVALID_INPUT",
            ex.getMessage(),
            System.currentTimeMillis()
        );
        
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error);
    }
    
    /**
     * 处理RuntimeException（未预期的错误）
     * 返回500 Internal Server Error
     * 
     * @param ex 运行时异常
     * @return 错误响应实体
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntimeException(RuntimeException ex) {
        log.error("[ChatController] Runtime error", ex);
        
        ErrorResponse error = new ErrorResponse(
            "INTERNAL_ERROR",
            "An unexpected error occurred. Please try again later.",
            System.currentTimeMillis()
        );
        
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error);
    }
    
    /**
     * 错误响应DTO
     */
    public static class ErrorResponse {
        /** 错误代码 (INVALID_INPUT, INTERNAL_ERROR等) */
        private String errorCode;
        
        /** 错误消息 */
        private String message;
        
        /** 时间戳 */
        private Long timestamp;
        
        public ErrorResponse(String errorCode, String message, Long timestamp) {
            this.errorCode = errorCode;
            this.message = message;
            this.timestamp = timestamp;
        }
        
        public String getErrorCode() {
            return errorCode;
        }
        
        public String getMessage() {
            return message;
        }
        
        public Long getTimestamp() {
            return timestamp;
        }
    }
}
