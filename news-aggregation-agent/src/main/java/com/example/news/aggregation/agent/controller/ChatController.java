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
import com.example.news.aggregation.agent.service.LLMOrchestrator;
import com.example.news.aggregation.agent.service.SessionManager;
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
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 对话与会话相关接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class ChatController {

    private final SessionManager sessionManager;
    private final LLMOrchestrator llmOrchestrator;
    private final ExecutionResumeService executionResumeService;
    private final ExecutionRunService executionRunService;
    private final ExecutionDispatchService executionDispatchService;

    /**
     * 对话入口。
     */
    @PostMapping("/chat")
    public ResponseEntity<AgentResponse> chat(@RequestBody ChatRequest request) {
        log.info("[api-step-01] 收到对话请求|sessionId={} |turnId={} |query={}",
                request.getSessionId(), request.getTurnId(), truncate(request.getQuery(), 120));
        try {
            if (request.getUserId() == null || request.getUserId().isBlank()) {
                request.setUserId("anonymous");
            }

            AgentResponse response = llmOrchestrator.handleChat(request);
            if ("SESSION_BUSY".equals(response.getErrorCode())) {
                log.warn("[api-step-02] 会话并发冲突|sessionId={} |turnId={} |runningTurnId={}",
                        response.getSessionId(), response.getTurnId(), response.getRunningTurnId());
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }
            if ("IDEMPOTENCY_IN_PROGRESS".equals(response.getErrorCode())) {
                log.info("[api-step-03] 幂等请求处理中|sessionId={} |turnId={} |runningTurnId={}",
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
     * 恢复 WAITING 状态的执行步骤。
     */
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
            log.warn("[api-step-resume] 恢复失败|runId={} |stepId={}", runId, stepId);
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
            log.error("[api-step-resume] 恢复调度失败，已回滚到WAITING|runId={} |stepId={}", runId, stepId);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "errorCode", "RESUME_DISPATCH_FAILED", "runId", runId, "stepId", stepId));
        }

        log.info("[api-step-resume] 恢复成功|runId={} |stepId={}", runId, stepId);
        ExecutionRunEntity latest = executionRunService.findByRunId(runId);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("runId", runId);
        body.put("stepId", stepId);
        body.put("status", latest == null ? "RUNNING" : latest.getStatus());
        return ResponseEntity.ok(body);
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
                .answer("请求处理失败：" + errorMessage)
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
