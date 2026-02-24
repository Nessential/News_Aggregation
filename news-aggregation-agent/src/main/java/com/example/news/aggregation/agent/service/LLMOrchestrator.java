package com.example.news.aggregation.agent.service;

import com.example.news.aggregation.agent.domain.AgentResponse;
import com.example.news.aggregation.agent.domain.PipelineContext;
import com.example.news.aggregation.agent.domain.PipelineResult;
import com.example.news.aggregation.agent.domain.SessionState;
import com.example.news.aggregation.agent.dto.ChatRequest;
import com.example.news.aggregation.agent.enums.ConversationState;
import com.example.news.aggregation.agent.enums.TaskFamily;
import com.example.news.aggregation.agent.fsm.ConversationFSM;
import com.example.news.aggregation.agent.fsm.FSMContext;
import com.example.news.aggregation.agent.tool.dto.RetrievalResult;
import com.example.news.aggregation.agent.workflow.WorkflowContext;
import com.example.news.aggregation.agent.workflow.WorkflowOrchestrator;
import com.example.news.aggregation.llm.springai.contract.Plan;
import com.example.news.aggregation.llm.springai.contract.RouterResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * LLM 编排器。
 * 统一负责 Router -> Planner -> Workflow -> FSM -> 响应组装 的端到端链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMOrchestrator {

    private final SessionManager sessionManager;
    private final com.example.news.aggregation.agent.client.RouterClient routerClient;
    private final com.example.news.aggregation.agent.client.PlannerClient plannerClient;
    private final WorkflowOrchestrator workflowOrchestrator;
    private final ActionComposer actionComposer;
    private final ConversationFSM conversationFSM;

    /**
     * 处理聊天请求。
     *
     * @param request 聊天请求
     * @return Agent 响应
     */
    public AgentResponse handleChat(ChatRequest request) {
        String userId = request.getUserId() != null ? request.getUserId() : "anonymous";
        SessionState sessionState = getOrCreateSession(request, userId);

        if (sessionState.isBudgetExhausted()) {
            return buildBudgetExhaustedResponse(sessionState);
        }

        sessionManager.addHistory(sessionState.getSessionId(), "User: " + request.getQuery());

        // FSM 进入 ROUTE
        transitionState(sessionState, ConversationState.ROUTE);

        // Router 分析
        RouterResult routerResult = routerClient.route(
                sessionState.getSessionId(),
                request.getQuery(),
                sessionState.getHistory(),
                sessionState.getConstraints() != null ? sessionState.getConstraints().toMap() : null
        );
        if (routerResult == null) {
            routerResult = RouterResult.defaultQA();
        }

        TaskFamily taskFamily = TaskFamily.valueOf(routerResult.getTaskFamily());

        // FSM 判断是否需要澄清
        FSMContext fsmContext = FSMContext.builder()
                .routerResult(routerResult)
                .needsClarification(routerResult.getNeedsClarification())
                .build();
        ConversationState nextAfterRoute = conversationFSM.nextState(ConversationState.ROUTE, fsmContext);
        transitionState(sessionState, nextAfterRoute);

        if (nextAfterRoute == ConversationState.NEED_CLARIFY) {
            return buildClarificationResponse(sessionState, routerResult);
        }

        // Workflow 执行：简单任务走显式工作流，复杂任务走 Planner
        WorkflowContext workflowContext = WorkflowContext.builder()
                .sessionId(sessionState.getSessionId())
                .query(request.getQuery())
                .taskFamily(routerResult.getTaskFamily())
                .build();
        Map<String, Object> filters = buildFilters(routerResult);
        if (filters != null && !filters.isEmpty()) {
            workflowContext.putAttribute("filters", filters);
        }

        boolean usePlanner = nextAfterRoute == ConversationState.PLAN || requiresPlanner(taskFamily);
        if (usePlanner) {
            if (sessionState.getConversationState() != ConversationState.PLAN) {
                transitionState(sessionState, ConversationState.PLAN);
            }
            Plan plan = plannerClient.plan(request.getQuery(), routerResult);
            if (plan == null || plan.getTasks() == null || plan.getTasks().isEmpty()) {
                plan = buildDefaultPlan(taskFamily, request.getQuery());
            }
            fsmContext.setPlan(plan);
            ConversationState nextAfterPlan = conversationFSM.nextState(ConversationState.PLAN, fsmContext);
            transitionState(sessionState, nextAfterPlan);
            workflowOrchestrator.executePlan(plan, workflowContext);
        } else {
            if (sessionState.getConversationState() != ConversationState.RETRIEVE) {
                transitionState(sessionState, ConversationState.RETRIEVE);
            }
            String workflowId = resolveWorkflowId(taskFamily);
            if (workflowOrchestrator.hasWorkflow(workflowId)) {
                workflowOrchestrator.executeWorkflow(workflowId, workflowContext);
            } else {
                Plan fallbackPlan = buildDefaultPlan(taskFamily, request.getQuery());
                workflowOrchestrator.executePlan(fallbackPlan, workflowContext);
            }
        }

        // FSM：构建证据 -> 生成 -> 校验
        fsmContext.setEvidenceCount(workflowContext.getEvidence().size());
        transitionState(sessionState, ConversationState.BUILD_EVIDENCE);
        ConversationState nextAfterEvidence = conversationFSM.nextState(ConversationState.BUILD_EVIDENCE, fsmContext);
        transitionState(sessionState, nextAfterEvidence);
        if (nextAfterEvidence == ConversationState.GENERATE) {
            transitionState(sessionState, ConversationState.VALIDATE);
        }

        PipelineResult pipelineResult = buildPipelineResultFromWorkflow(workflowContext);

        // 消耗预算
        sessionManager.consumeBudget(sessionState.getSessionId(), pipelineResult.getLlmCallCount());

        // 组装响应
        PipelineContext context = PipelineContext.builder()
                .sessionState(sessionState)
                .routerResult(routerResult)
                .query(request.getQuery())
                .build();
        AgentResponse response = actionComposer.buildResponse(pipelineResult, context, taskFamily);

        // 更新会话状态
        if (pipelineResult.getCandidateIds() != null) {
            sessionManager.updateCandidates(sessionState.getSessionId(), pipelineResult.getCandidateIds());
        }
        sessionManager.addHistory(sessionState.getSessionId(), "Agent: " + response.getAnswer());
        transitionState(sessionState, ConversationState.DONE);

        return response;
    }

    private SessionState getOrCreateSession(ChatRequest request, String userId) {
        if (request.getSessionId() != null) {
            SessionState existing = sessionManager.getSession(request.getSessionId());
            if (existing != null) {
                return existing;
            }
        }
        return sessionManager.createSession(userId);
    }

    private boolean requiresPlanner(TaskFamily taskFamily) {
        return taskFamily == TaskFamily.COMPARE
                || taskFamily == TaskFamily.DEEP_DIVE;
    }

    /**
     * 选择显式工作流 ID。
     */
    private String resolveWorkflowId(TaskFamily taskFamily) {
        if (taskFamily == null) {
            return null;
        }
        return switch (taskFamily) {
            case QA -> "QA_WORKFLOW";
            case SUMMARY -> "SUMMARY_WORKFLOW";
            case TIMELINE -> "TIMELINE_WORKFLOW";
            default -> null;
        };
    }

    private Plan buildDefaultPlan(TaskFamily taskFamily, String query) {
        List<Plan.Task> tasks = new ArrayList<>();
        tasks.add(Plan.Task.builder()
                .id("task-1")
                .type("SEARCH")
                .description("关键词检索")
                .parameters(java.util.Map.of("query", query))
                .build());
        tasks.add(Plan.Task.builder()
                .id("task-2")
                .type("RETRIEVE")
                .description("向量检索")
                .parameters(java.util.Map.of("query", query, "mode", "HYBRID"))
                .build());
        tasks.add(Plan.Task.builder()
                .id("task-3")
                .type(taskFamily == TaskFamily.QA ? "QA" : taskFamily.name())
                .description("生成答案")
                .parameters(java.util.Map.of("query", query, "taskFamily", taskFamily.name()))
                .build());

        return Plan.builder()
                .tasks(tasks)
                .executionOrder(List.of("task-1", "task-2", "task-3"))
                .totalEstimatedTime(10)
                .parallelizable(false)
                .build();
    }

    private PipelineResult buildPipelineResultFromWorkflow(WorkflowContext workflowContext) {
        List<Long> candidateIds = workflowContext.getEvidence().stream()
                .map(RetrievalResult::getArticleId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        String answer = workflowContext.getAttributes().getOrDefault("answer", "").toString();
        List<String> citations = extractCitations(workflowContext.getAttributes().get("citations"));

        return PipelineResult.builder()
                .answer(answer)
                .candidateIds(candidateIds)
                .citations(citations)
                .llmCallCount(answer.isEmpty() ? 0 : 1)
                .executionTimeMs(0L)
                .success(true)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractCitations(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List) {
            List<Object> list = (List<Object>) raw;
            return list.stream()
                    .map(item -> {
                        if (item == null) {
                            return "";
                        }
                        if (item instanceof com.example.news.aggregation.llm.springai.contract.GeneratorDraft.Citation citation) {
                            return citation.getSourceId();
                        }
                        return String.valueOf(item);
                    })
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toList());
        }
        return List.of(String.valueOf(raw));
    }

    private AgentResponse buildBudgetExhaustedResponse(SessionState sessionState) {
        return AgentResponse.builder()
                .sessionId(sessionState.getSessionId())
                .answer("抱歉，当前会话的预算已耗尽，请创建新会话继续对话。")
                .timestamp(LocalDateTime.now())
                .metadata(AgentResponse.ResponseMetadata.builder()
                        .remainingBudget(0)
                        .build())
                .build();
    }

    private AgentResponse buildClarificationResponse(SessionState sessionState, RouterResult routerResult) {
        return AgentResponse.builder()
                .sessionId(sessionState.getSessionId())
                .answer("我需要更多信息才能回答您的问题。")
                .needsClarification(true)
                .clarificationPrompt(routerResult.getClarificationQuestion())
                .timestamp(LocalDateTime.now())
                .build();
    }

    private Map<String, Object> buildFilters(RouterResult routerResult) {
        if (routerResult == null || routerResult.getParams() == null) {
            return null;
        }
        Map<String, Object> params = routerResult.getParams();
        Map<String, Object> filters = new java.util.HashMap<>();
        putIfPresent(filters, "timeRange", params, "timeRange", "time_range", "time-range");
        putIfPresent(filters, "startDate", params, "startDate", "start_date");
        putIfPresent(filters, "endDate", params, "endDate", "end_date");
        putIfPresent(filters, "keywords", params, "keywords");
        putIfPresent(filters, "topic", params, "topic");
        putIfPresent(filters, "category", params, "category");
        putIfPresent(filters, "language", params, "language", "lang");
        putIfPresent(filters, "region", params, "region");
        putIfPresent(filters, "source", params, "source");
        putIfPresent(filters, "publisher", params, "publisher");
        putIfPresent(filters, "sortBy", params, "sortBy", "sort_by");
        return filters.isEmpty() ? null : filters;
    }

    private void putIfPresent(Map<String, Object> target,
                              String normalizedKey,
                              Map<String, Object> source,
                              String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) {
                Object value = source.get(key);
                if (value != null) {
                    target.put(normalizedKey, value);
                    return;
                }
            }
        }
    }

    /**
     * 执行 FSM 状态迁移并做合法性校验。
     */
    private void transitionState(SessionState sessionState, ConversationState target) {
        if (sessionState == null || target == null) {
            return;
        }
        ConversationState current = sessionState.getConversationState();
        try {
            conversationFSM.validateTransition(current, target);
            sessionManager.updateConversationState(sessionState.getSessionId(), target);
            sessionState.setConversationState(target);
        } catch (Exception e) {
            sessionManager.updateConversationState(sessionState.getSessionId(), ConversationState.FAIL_SAFE);
            sessionState.setConversationState(ConversationState.FAIL_SAFE);
            log.warn("FSM transition rejected: {} -> {}, error={}", current, target, e.getMessage());
        }
    }
}
