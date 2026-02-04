package com.example.news.aggregation.agent.controller;

import com.example.news.aggregation.agent.client.RouterClient;
import com.example.news.aggregation.agent.domain.*;
import com.example.news.aggregation.agent.dto.ChatRequest;
import com.example.news.aggregation.agent.dto.CreateSessionRequest;
import com.example.news.aggregation.agent.dto.SessionResponse;
import com.example.news.aggregation.agent.enums.ConversationState;
import com.example.news.aggregation.agent.enums.TaskFamily;
import com.example.news.aggregation.agent.service.ActionComposer;
import com.example.news.aggregation.agent.service.PipelineExecutor;
import com.example.news.aggregation.agent.service.SessionManager;
import com.example.news.aggregation.llm.springai.contract.RouterResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * Chat 控制器
 * 负责对话入口、会话管理、路由判定与响应组装
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class ChatController {

    private final SessionManager sessionManager;
    private final PipelineExecutor pipelineExecutor;
    private final ActionComposer actionComposer;
    private final RouterClient routerClient;

    /**
     * 对话入口：接收用户 query 并返回 AgentResponse
     */
    @PostMapping("/chat")
    public ResponseEntity<AgentResponse> chat(@RequestBody ChatRequest request) {
        log.info("接受query: sessionId={}, query={}",
                request.getSessionId(), request.getQuery());

        try {
            String userId = "忘川的哈基鸟";
            request.setUserId(userId);
            // 1. 获取或创建 Session
            SessionState sessionState = getOrCreateSession(request);

            // 2. 检查预算
            if (sessionState.isBudgetExhausted()) {
                log.warn("预算不充足: {}", sessionState.getSessionId());
                return ResponseEntity.ok(buildBudgetExhaustedResponse(sessionState));
            }

            // 3. 追加对话历史
            sessionManager.addHistory(sessionState.getSessionId(), "User: " + request.getQuery());

            // 4. Router 分析（优先调用 Router 服务，失败回退规则）
            RouterResult routerResult = routerClient.route(
                    sessionState.getSessionId(),
                    request.getQuery(),
                    sessionState.getHistory(),
                    sessionState.getConstraints() != null ? sessionState.getConstraints().toMap() : null
            );
            if (routerResult == null) {
                routerResult = buildDefaultRouterResult(request);
            }
            log.info("任务类型{}",routerResult.getTaskFamily());
            TaskFamily taskFamily = TaskFamily.valueOf(routerResult.getTaskFamily());

            // 5. 若需追问则直接返回
            if (routerResult.getNeedsClarification() != null && routerResult.getNeedsClarification()) {
                return ResponseEntity.ok(buildClarificationResponse(sessionState, routerResult));
            }

            // 6. 构建 PipelineContext 统一的上下问容器
            PipelineContext context = PipelineContext.builder()
                    .sessionState(sessionState)
                    .routerResult(routerResult)
                    .query(request.getQuery())
                    .build();

            // 7. 执行 Pipeline
            sessionManager.updateConversationState(sessionState.getSessionId(), ConversationState.RETRIEVE);
            PipelineResult pipelineResult = pipelineExecutor.execute(context);

            // 8. 消耗预算
            sessionManager.consumeBudget(sessionState.getSessionId(), pipelineResult.getLlmCallCount());

            // 9. 组装响应
            sessionManager.updateConversationState(sessionState.getSessionId(), ConversationState.COMPOSE);
            AgentResponse response = actionComposer.buildResponse(pipelineResult, context, taskFamily);

            // 10. 更新 Session
            if (pipelineResult.getCandidateIds() != null) {
                sessionManager.updateCandidates(sessionState.getSessionId(), pipelineResult.getCandidateIds());
            }
            sessionManager.addHistory(sessionState.getSessionId(), "Agent: " + response.getAnswer());
            sessionManager.updateConversationState(sessionState.getSessionId(), ConversationState.DONE);

            log.info("Chat request completed: sessionId={}, executionTime={}ms",
                    sessionState.getSessionId(), response.getExecutionTimeMs());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Chat request failed", e);
            return ResponseEntity.status(500).body(buildErrorResponse(
                    request.getSessionId(),
                    "Internal error: " + e.getMessage()
            ));
        }
    }

    /**
     * 创建新会话
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
     * 查询会话状态
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
     * 删除会话
     */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        sessionManager.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    // ========== Private Methods ==========

    /**
     * 获取或创建 Session
     */
    private SessionState getOrCreateSession(ChatRequest request) {
        if (request.getSessionId() != null) {
            SessionState existing = sessionManager.getSession(request.getSessionId());
            if (existing != null) {
                return existing;
            }
        }
        // 创建新会话
        return sessionManager.createSession(request.getUserId() != null ? request.getUserId() : "anonymous");
    }

    /**
     * 构建默认 RouterResult（简化规则实现）
     * TODO: 后续改为 LLM Router (Function Calling/JSON Schema) 的结果
     */
    private RouterResult buildDefaultRouterResult(ChatRequest request) {
        // 简单规则判断 TaskFamily
        String query = request.getQuery().toLowerCase();
        String taskFamily;

        if (query.contains("总结") || query.contains("汇总") || query.contains("summary")) {
            taskFamily = "SUMMARY";
        } else if (query.contains("搜索") || query.contains("查找") || query.contains("search")) {
            taskFamily = "SEARCH";
        } else {
            taskFamily = "QA";
        }

        return RouterResult.builder()
                .taskFamily(taskFamily)
                .needsClarification(false)
                .build();
    }

    /**
     * 构建预算耗尽响应
     */
    private AgentResponse buildBudgetExhaustedResponse(SessionState sessionState) {
        return AgentResponse.builder()
                .sessionId(sessionState.getSessionId())
                .answer("抱歉，当前会话的使用额度已用完。请创建新会话继续对话。")
                .timestamp(LocalDateTime.now())
                .metadata(AgentResponse.ResponseMetadata.builder()
                        .remainingBudget(0)
                        .build())
                .build();
    }

    /**
     * 构建追问响应
     */
    private AgentResponse buildClarificationResponse(SessionState sessionState, RouterResult routerResult) {
        return AgentResponse.builder()
                .sessionId(sessionState.getSessionId())
                .answer("我需要更多信息来回答您的问题。")
                .needsClarification(true)
                .clarificationPrompt(routerResult.getClarificationQuestion())
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * 构建错误响应
     */
    private AgentResponse buildErrorResponse(String sessionId, String errorMessage) {
        return AgentResponse.builder()
                .sessionId(sessionId)
                .answer("抱歉，处理您的请求时出现错误：" + errorMessage)
                .timestamp(LocalDateTime.now())
                .build();
    }
}