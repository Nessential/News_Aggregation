package com.example.news.aggregation.agent.service;

import com.example.news.aggregation.agent.client.PlannerClient;
import com.example.news.aggregation.agent.client.RouterClient;
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
import com.example.news.aggregation.agent.workflow.WorkflowDefinition;
import com.example.news.aggregation.agent.workflow.WorkflowOrchestrator;
import com.example.news.aggregation.agent.workflow.WorkflowStep;
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
    private final RouterClient routerClient;
    private final PlannerClient plannerClient;
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
        String sessionId = sessionState.getSessionId();
        log.info("[链路最终] 开始处理对话: sessionId={}, userId={}, query={}", sessionId, userId, request.getQuery());

        if (sessionState.isBudgetExhausted()) {
            return buildBudgetExhaustedResponse(sessionState);
        }

        sessionManager.addHistory(sessionId, "User: " + request.getQuery());

        // 1. FSM 进入 ROUTE
        log.info("[fsm] 进入路由阶段FLOW|agent|fsm|from={}|to={}|reason=收到用户请求|next=Router",
                sessionState.getConversationState(), ConversationState.ROUTE);
        transitionState(sessionState, ConversationState.ROUTE);

        // 2. Router 分析
        RouterResult routerResult = routerClient.route(
                sessionId,
                request.getQuery(),
                sessionState.getHistory(),
                sessionState.getConstraints() != null ? sessionState.getConstraints().toMap() : null
        );
        if (routerResult == null) {
            routerResult = RouterResult.defaultQA();
        }
        log.info("[链路最终] 路由完成: sessionId={}, taskFamily={}, retrievalMode={}, needsClarification={}",
                sessionId, routerResult.getTaskFamily(), routerResult.getRetrievalMode(), routerResult.getNeedsClarification());

        TaskFamily taskFamily = TaskFamily.valueOf(routerResult.getTaskFamily());
        boolean directAnswer = "NONE".equalsIgnoreCase(routerResult.getRetrievalMode());

        // 3. FSM 判断是否需要澄清或规划
        FSMContext fsmContext = FSMContext.builder()
                .routerResult(routerResult)
                .needsClarification(routerResult.getNeedsClarification())
                .directAnswer(directAnswer)
                .build();
        ConversationState nextAfterRoute = conversationFSM.nextState(ConversationState.ROUTE, fsmContext);
        String routeReason;
        if (Boolean.TRUE.equals(routerResult.getNeedsClarification())) {
            routeReason = "需要澄清参数";
        } else if (directAnswer) {
            routeReason = "无需检索直接回答";
        } else if (requiresPlanner(taskFamily)) {
            routeReason = "复杂任务需要规划";
        } else {
            routeReason = "默认进入检索";
        }
        log.info("[fsm] 路由决策FLOW|agent|fsm|from=ROUTE|to={}|reason={}|next={}",
                nextAfterRoute,
                routeReason,
                nextAfterRoute == ConversationState.NEED_CLARIFY ? "Clarification" :
                        (nextAfterRoute == ConversationState.PLAN ? "Planner" : "Workflow"));
        transitionState(sessionState, nextAfterRoute);

        if (nextAfterRoute == ConversationState.NEED_CLARIFY) {
            return buildClarificationResponse(sessionState, routerResult);
        }

        // 4. 组装工作流上下文
        WorkflowContext workflowContext = WorkflowContext.builder()
                .sessionId(sessionId)
                .query(request.getQuery())
                .taskFamily(routerResult.getTaskFamily())
                .build();
        workflowContext.putAttribute("retrievalMode", routerResult.getRetrievalMode());
        Map<String, Object> filters = buildFilters(routerResult);
        if (filters != null && !filters.isEmpty()) {
            workflowContext.putAttribute("filters", filters);
        }

        // 5. 执行规划或显式工作流
        if (nextAfterRoute == ConversationState.BUILD_EVIDENCE && directAnswer) {
            log.info("[fsm] 进入证据构建FLOW|agent|fsm|from={}|to=BUILD_EVIDENCE|reason=无需检索直答|next=EvidenceCheck",
                    sessionState.getConversationState());
            transitionState(sessionState, ConversationState.BUILD_EVIDENCE);
            WorkflowDefinition directWorkflow = buildDirectWorkflow(routerResult.getTaskFamily(), routerResult.getRetrievalMode());
            workflowOrchestrator.executeWorkflow(directWorkflow, workflowContext);
        } else {
            boolean usePlanner = nextAfterRoute == ConversationState.PLAN || requiresPlanner(taskFamily);
            log.info("[链路最终] 执行策略: sessionId={}, usePlanner={}, taskFamily={}", sessionId, usePlanner, taskFamily);
            if (usePlanner) {
                if (sessionState.getConversationState() != ConversationState.PLAN) {
                    log.info("[fsm] 进入规划阶段FLOW|agent|fsm|from={}|to=PLAN|reason=复杂任务或路由要求|next=Planner",
                            sessionState.getConversationState());
                    transitionState(sessionState, ConversationState.PLAN);
                }
                Plan plan = plannerClient.plan(request.getQuery(), routerResult);
                if (plan == null || plan.getTasks() == null || plan.getTasks().isEmpty()) {
                    plan = buildDefaultPlan(taskFamily, request.getQuery());
                }
                int planTaskCount = plan.getTasks() != null ? plan.getTasks().size() : 0;
                log.info("[链路最终] 规划结果: sessionId={}, taskCount={}", sessionId, planTaskCount);
                fsmContext.setPlan(plan);
                ConversationState nextAfterPlan = conversationFSM.nextState(ConversationState.PLAN, fsmContext);
                log.info("[fsm] 规划后状态FLOW|agent|fsm|from=PLAN|to={}|reason=计划已生成|next=Workflow", nextAfterPlan);
                transitionState(sessionState, nextAfterPlan);
                workflowOrchestrator.executePlan(plan, workflowContext);
            } else {
                if (sessionState.getConversationState() != ConversationState.RETRIEVE) {
                    log.info("[fsm] 进入检索阶段FLOW|agent|fsm|from={}|to=RETRIEVE|reason=无需规划|next=Workflow",
                            sessionState.getConversationState());
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
        }

        log.info("[链路最终] 检索结束: sessionId={}, evidenceCount={}", sessionId, workflowContext.getEvidence().size());

        // 6. FSM：构建证据 -> 生成 -> 校验
        fsmContext.setEvidenceCount(workflowContext.getEvidence().size());
        log.info("[fsm] 进入证据构建FLOW|agent|fsm|from={}|to=BUILD_EVIDENCE|reason=检索结束|next=EvidenceCheck",
                sessionState.getConversationState());
        transitionState(sessionState, ConversationState.BUILD_EVIDENCE);
        ConversationState nextAfterEvidence = conversationFSM.nextState(ConversationState.BUILD_EVIDENCE, fsmContext);
        log.info("[fsm] 证据判断FLOW|agent|fsm|from=BUILD_EVIDENCE|to={}|reason=evidenceCount={}|next={}",
                nextAfterEvidence,
                workflowContext.getEvidence().size(),
                nextAfterEvidence == ConversationState.GENERATE ? "Generate" : "Retrieve");
        transitionState(sessionState, nextAfterEvidence);
        if (nextAfterEvidence == ConversationState.GENERATE) {
            log.info("[fsm] 进入生成校验FLOW|agent|fsm|from=GENERATE|to=VALIDATE|reason=证据充足|next=Validate",
                    sessionState.getConversationState());
            transitionState(sessionState, ConversationState.VALIDATE);
        }

        PipelineResult pipelineResult = buildPipelineResultFromWorkflow(workflowContext);
        int answerLength = pipelineResult.getAnswer() != null ? pipelineResult.getAnswer().length() : 0;
        int candidateCount = pipelineResult.getCandidateIds() != null ? pipelineResult.getCandidateIds().size() : 0;
        int citationCount = pipelineResult.getCitations() != null ? pipelineResult.getCitations().size() : 0;
        log.info("[链路最终] 生成结果: sessionId={}, answerLength={}, candidateCount={}, citationCount={}",
                sessionId, answerLength, candidateCount, citationCount);

        // 7. 消耗预算
        sessionManager.consumeBudget(sessionId, pipelineResult.getLlmCallCount());

        // 8. 组装响应
        PipelineContext context = PipelineContext.builder()
                .sessionState(sessionState)
                .routerResult(routerResult)
                .query(request.getQuery())
                .build();
        AgentResponse response = actionComposer.buildResponse(pipelineResult, context, taskFamily);

        // 9. 更新会话状态
        if (pipelineResult.getCandidateIds() != null) {
            sessionManager.updateCandidates(sessionId, pipelineResult.getCandidateIds());
        }
        sessionManager.addHistory(sessionId, "Agent: " + response.getAnswer());
        log.info("[fsm] 对话结束FLOW|agent|fsm|from={}|to=DONE|reason=响应已生成|next=结束",
                sessionState.getConversationState());
        transitionState(sessionState, ConversationState.DONE);
        log.info("[链路最终] 响应完成: sessionId={}, taskFamily={}", sessionId, taskFamily);

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

    private WorkflowDefinition buildDirectWorkflow(String taskFamily, String retrievalMode) {
        return WorkflowDefinition.builder()
                .id("DIRECT_QA_WORKFLOW")
                .name("直答工作流")
                .steps(List.of(
                        WorkflowStep.builder()
                                .stepId("direct-generate")
                                .capabilityName("llm_generate")
                                .parameters(java.util.Map.of(
                                        "taskFamily", taskFamily != null ? taskFamily : "QA",
                                        "retrievalMode", retrievalMode != null ? retrievalMode : "NONE"
                                ))
                                .build()
                ))
                .metadata(java.util.Map.of("type", "DIRECT"))
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
            log.info("[fsm] FSM 过渡: sessionId={}, from={}, to={}", sessionState.getSessionId(), current, target);
        } catch (Exception e) {
            sessionManager.updateConversationState(sessionState.getSessionId(), ConversationState.FAIL_SAFE);
            sessionState.setConversationState(ConversationState.FAIL_SAFE);
            log.warn("[fsm] FSM 过渡被拒绝: {} -> {}, error={}", current, target, e.getMessage());
        }
    }
}