package com.example.news.aggregation.agent.service;

import com.example.news.aggregation.agent.client.PlannerClient;
import com.example.news.aggregation.agent.client.RouterClient;
import com.example.news.aggregation.agent.domain.AgentResponse;
import com.example.news.aggregation.agent.domain.IdempotencyRecord;
import com.example.news.aggregation.agent.domain.PipelineContext;
import com.example.news.aggregation.agent.domain.PipelineResult;
import com.example.news.aggregation.agent.domain.SessionState;
import com.example.news.aggregation.agent.domain.TurnState;
import com.example.news.aggregation.agent.dto.ChatRequest;
import com.example.news.aggregation.agent.enums.ConversationState;
import com.example.news.aggregation.agent.enums.IdempotencyStatus;
import com.example.news.aggregation.agent.enums.TaskFamily;
import com.example.news.aggregation.agent.enums.TurnStatus;
import com.example.news.aggregation.agent.fsm.ConversationFSM;
import com.example.news.aggregation.agent.fsm.FSMContext;
import com.example.news.aggregation.agent.tool.dto.RetrievalResult;
import com.example.news.aggregation.agent.workflow.WorkflowContext;
import com.example.news.aggregation.agent.workflow.WorkflowDefinition;
import com.example.news.aggregation.agent.workflow.WorkflowOrchestrator;
import com.example.news.aggregation.agent.workflow.WorkflowStep;
import com.example.news.aggregation.llm.springai.contract.ExecutionPlan;
import com.example.news.aggregation.llm.springai.contract.RouterResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 统一编排入口：Router -> Planner/Workflow -> FSM -> Response。
 * <p>
 * 关键改造点：
 * 1) 每个请求对应一个独立 turn；2) 会话级锁保证同 session 串行；
 * 3) turn 级幂等，避免客户端重试导致重复执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMOrchestrator {

    private final SessionManager sessionManager;
    private final TurnManager turnManager;
    private final RouterClient routerClient;
    private final PlannerClient plannerClient;
    private final WorkflowOrchestrator workflowOrchestrator;
    private final ActionComposer actionComposer;
    private final ConversationFSM conversationFSM;

    /**
     * 对话处理入口。
     * 覆盖会话串行、幂等回放、单轮执行、结果落库与异常收敛。
     */
    public AgentResponse handleChat(ChatRequest request) {
        String userId = request.getUserId() != null ? request.getUserId() : "anonymous";
        SessionState sessionState = getOrCreateSession(request, userId);
        String sessionId = sessionState.getSessionId();
        String turnId = resolveTurnId(request);
        request.setSessionId(sessionId);
        request.setTurnId(turnId);
        String requestHash = buildRequestHash(request);
        String idempotencyKey = resolveIdempotencyKey(request, sessionId, turnId);
        request.setIdempotencyKey(idempotencyKey);

        log.info("[turn-step-01] 接收请求: sessionId={}, turnId={}, idempotencyKey={}, userId={}, query={}",
                sessionId, turnId, idempotencyKey, userId, truncate(request.getQuery(), 200));

        // 步骤2：按 idempotencyKey 查询记录。
        IdempotencyRecord idempotencyRecord = turnManager.getIdempotencyRecord(sessionId, idempotencyKey);
        AgentResponse replayFromRecord = replayFromIdempotencyRecord(sessionId, turnId, idempotencyRecord);
        if (replayFromRecord != null) {
            log.info("[turn-step-02] 幂等命中: sessionId={}, turnId={}, idempotencyKey={}, status={}",
                    sessionId, turnId, idempotencyKey, idempotencyRecord.getStatus());
            return replayFromRecord;
        }

        // 步骤3：会话串行锁。
        if (!sessionManager.tryAcquireSessionLock(sessionId, turnId)) {
            // 锁冲突时再查一次幂等，避免“同 key 重试”误返回 409。
            IdempotencyRecord retryRecord = turnManager.getIdempotencyRecord(sessionId, idempotencyKey);
            AgentResponse retryReplay = replayFromIdempotencyRecord(sessionId, turnId, retryRecord);
            if (retryReplay != null) {
                log.info("[turn-step-03] 锁冲突但幂等可回放: sessionId={}, turnId={}, idempotencyKey={}, status={}",
                        sessionId, turnId, idempotencyKey, retryRecord.getStatus());
                return retryReplay;
            }
            String runningTurnId = sessionManager.getRunningTurnId(sessionId);
            log.warn("[turn-step-03] 会话忙，拒绝并发请求: sessionId={}, turnId={}, runningTurnId={}",
                    sessionId, turnId, runningTurnId);
            return buildBusyResponse(sessionId, turnId, runningTurnId);
        }

        try {
            // 步骤4：创建 IN_PROGRESS 幂等记录。失败说明已有并发写入，按记录回放。
            boolean inProgressCreated = turnManager.tryCreateInProgressRecord(
                    sessionId,
                    idempotencyKey,
                    turnId,
                    requestHash
            );
            if (!inProgressCreated) {
                IdempotencyRecord existing = turnManager.getIdempotencyRecord(sessionId, idempotencyKey);
                AgentResponse replay = replayFromIdempotencyRecord(sessionId, turnId, existing);
                if (replay != null) {
                    log.info("[turn-step-04] 幂等记录已存在，直接回放: sessionId={}, turnId={}, idempotencyKey={}, status={}",
                            sessionId, turnId, idempotencyKey, existing.getStatus());
                    return replay;
                }
            }

            // 步骤5：创建运行中 turn，并将本轮 FSM 起点重置到 START。
            turnManager.createRunningTurn(sessionId, turnId, requestHash);

            AgentResponse response = executeSingleTurn(request, sessionState, turnId);
            response.setSessionId(sessionId);
            response.setTurnId(turnId);

            if (response.getTurnStatus() == null || response.getTurnStatus().isBlank()) {
                response.setTurnStatus(TurnStatus.DONE.name());
            }

            if (TurnStatus.FAILED.name().equalsIgnoreCase(response.getTurnStatus())) {
                String errorCode = response.getErrorCode() != null ? response.getErrorCode() : "TURN_EXECUTION_FAILED";
                String errorMessage = response.getAnswer() != null ? response.getAnswer() : "turn failed";
                turnManager.markTurnFailed(sessionId, turnId, errorCode, errorMessage);
                turnManager.markIdempotencyFailed(
                        sessionId,
                        idempotencyKey,
                        turnId,
                        errorCode,
                        errorMessage,
                        response
                );
                return response;
            }

            turnManager.markTurnDone(sessionId, turnId, response);
            turnManager.markIdempotencyDone(sessionId, idempotencyKey, turnId, response);
            log.info("[turn-step-99] 本轮完成: sessionId={}, turnId={}", sessionId, turnId);
            return response;
        } catch (Exception e) {
            log.error("[turn-step-xx] 本轮失败: sessionId={}, turnId={}", sessionId, turnId, e);
            AgentResponse failed = AgentResponse.builder()
                    .sessionId(sessionId)
                    .turnId(turnId)
                    .turnStatus(TurnStatus.FAILED.name())
                    .errorCode("TURN_EXECUTION_FAILED")
                    .answer("抱歉，处理本轮请求时出现异常：" + e.getMessage())
                    .timestamp(LocalDateTime.now())
                    .build();
            turnManager.markTurnFailed(sessionId, turnId, failed.getErrorCode(), e.getMessage());
            turnManager.markIdempotencyFailed(
                    sessionId,
                    idempotencyKey,
                    turnId,
                    failed.getErrorCode(),
                    e.getMessage(),
                    failed
            );
            return failed;
        } finally {
            sessionManager.releaseSessionLock(sessionId, turnId);
        }
    }

    /**
     * 执行单轮对话主流程。
     * 路由后按任务复杂度选择“直接工作流”或“Planner + ExecutionPlan”执行。
     */
    private AgentResponse executeSingleTurn(ChatRequest request, SessionState sessionState, String turnId) {
        String sessionId = sessionState.getSessionId();
        ConversationState currentFsmState = getCurrentTurnFsmState(sessionId, turnId);

        if (sessionState.isBudgetExhausted()) {
            return buildBudgetExhaustedResponse(sessionState, turnId);
        }

        sessionManager.addHistory(sessionId, "User: " + request.getQuery());

        log.info("[fsm-step-01] 进入路由: sessionId={}, turnId={}, from={}, to={}",
                sessionId, turnId, currentFsmState, ConversationState.ROUTE);
        currentFsmState = transitionState(sessionId, turnId, currentFsmState, ConversationState.ROUTE);

        RouterResult routerResult = routerClient.route(
                sessionId,
                request.getQuery(),
                sessionState.getHistory(),
                sessionState.getConstraints() != null ? sessionState.getConstraints().toMap() : null
        );
        if (routerResult == null) {
            routerResult = RouterResult.defaultQA();
        }
        normalizeRetrievalMode(routerResult);

        TaskFamily taskFamily = TaskFamily.valueOf(routerResult.getTaskFamily());
        boolean directAnswer = "NONE".equalsIgnoreCase(routerResult.getRetrievalMode());
        FSMContext fsmContext = FSMContext.builder()
                .routerResult(routerResult)
                .needsClarification(routerResult.getNeedsClarification())
                .directAnswer(directAnswer)
                .build();

        ConversationState nextAfterRoute = conversationFSM.nextState(ConversationState.ROUTE, fsmContext);
        log.info("[fsm-step-02] 路由决策: sessionId={}, turnId={}, taskFamily={}, retrievalMode={}, next={}",
                sessionId, turnId, taskFamily, routerResult.getRetrievalMode(), nextAfterRoute);
        currentFsmState = transitionState(sessionId, turnId, currentFsmState, nextAfterRoute);

        if (nextAfterRoute == ConversationState.NEED_CLARIFY) {
            currentFsmState = transitionState(sessionId, turnId, currentFsmState, ConversationState.WAITING);
            return buildClarificationResponse(sessionState, routerResult, turnId);
        }

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

        if (nextAfterRoute == ConversationState.BUILD_EVIDENCE && directAnswer) {
            log.info("[orchestrator] 进入直答分支：sessionId={}, turnId={}, taskFamily={}, retrievalMode={}",
                    sessionId, turnId, routerResult.getTaskFamily(), routerResult.getRetrievalMode());
            if (currentFsmState != ConversationState.BUILD_EVIDENCE) {
                currentFsmState = transitionState(sessionId, turnId, currentFsmState, ConversationState.BUILD_EVIDENCE);
            }
            WorkflowDefinition directWorkflow = buildDirectWorkflow(
                    routerResult.getTaskFamily(),
                    routerResult.getRetrievalMode()
            );
            workflowOrchestrator.executeWorkflow(directWorkflow, workflowContext);
        } else {
            boolean usePlanner = nextAfterRoute == ConversationState.PLAN || requiresPlanner(taskFamily);
            if (usePlanner) {
                log.info("[orchestrator] 进入规划分支：sessionId={}, turnId={}, taskFamily={}",
                        sessionId, turnId, taskFamily);
                if (currentFsmState != ConversationState.PLAN) {
                    currentFsmState = transitionState(sessionId, turnId, currentFsmState, ConversationState.PLAN);
                }
                ExecutionPlan plan = plannerClient.plan(request.getQuery(), routerResult);
                requireValidPlan(plan, taskFamily, request.getQuery());
                log.info("[orchestrator] 规划完成：sessionId={}, turnId={}, planId={}, stepCount={}",
                        sessionId, turnId, plan.getPlanId(), plan.getSteps() == null ? 0 : plan.getSteps().size());
                fsmContext.setExecutionPlan(plan);
                ConversationState nextAfterPlan = conversationFSM.nextState(ConversationState.PLAN, fsmContext);
                currentFsmState = transitionState(sessionId, turnId, currentFsmState, nextAfterPlan);
                workflowOrchestrator.executePlan(plan, workflowContext);
            } else {
                log.info("[orchestrator] 进入固定工作流分支：sessionId={}, turnId={}, taskFamily={}",
                        sessionId, turnId, taskFamily);
                if (currentFsmState != ConversationState.RETRIEVE) {
                    currentFsmState = transitionState(sessionId, turnId, currentFsmState, ConversationState.RETRIEVE);
                }
                String workflowId = resolveWorkflowId(taskFamily);
                if (workflowOrchestrator.hasWorkflow(workflowId)) {
                    log.info("[orchestrator] 开始执行固定工作流：sessionId={}, turnId={}, workflowId={}",
                            sessionId, turnId, workflowId);
                    workflowOrchestrator.executeWorkflow(workflowId, workflowContext);
                } else {
                    throw new IllegalStateException("WORKFLOW_NOT_FOUND:" + workflowId + ", taskFamily=" + taskFamily);
                }
            }
        }

        if (Boolean.TRUE.equals(workflowContext.getAttributes().get("workflow.waiting"))) {
            String waitingReason = String.valueOf(workflowContext.getAttributes()
                    .getOrDefault("workflow.waiting.reason", "需要更多输入信息"));
            log.info("[orchestrator] 工作流进入等待：sessionId={}, turnId={}, reason={}",
                    sessionId, turnId, waitingReason);
            currentFsmState = transitionState(sessionId, turnId, currentFsmState, ConversationState.WAITING);
            return buildWaitingResponse(sessionState, turnId, waitingReason);
        }

        fsmContext.setEvidenceCount(workflowContext.getEvidence().size());
        if (currentFsmState != ConversationState.BUILD_EVIDENCE) {
            currentFsmState = transitionState(sessionId, turnId, currentFsmState, ConversationState.BUILD_EVIDENCE);
        }

        ConversationState nextAfterEvidence = conversationFSM.nextState(ConversationState.BUILD_EVIDENCE, fsmContext);
        currentFsmState = transitionState(sessionId, turnId, currentFsmState, nextAfterEvidence);

        if (nextAfterEvidence == ConversationState.GENERATE
                && currentFsmState != ConversationState.VALIDATE) {
            currentFsmState = transitionState(sessionId, turnId, currentFsmState, ConversationState.VALIDATE);
        }

        PipelineResult pipelineResult = buildPipelineResultFromWorkflow(workflowContext);
        sessionManager.consumeBudget(sessionId, pipelineResult.getLlmCallCount());

        PipelineContext context = PipelineContext.builder()
                .sessionState(sessionState)
                .routerResult(routerResult)
                .query(request.getQuery())
                .build();
        AgentResponse response = actionComposer.buildResponse(pipelineResult, context, taskFamily);
        log.info("[orchestrator] 响应组装完成：sessionId={}, turnId={}, answerLength={}, citationCount={}, candidateCount={}",
                sessionId,
                turnId,
                response.getAnswer() == null ? 0 : response.getAnswer().length(),
                response.getCitations() == null ? 0 : response.getCitations().size(),
                response.getCandidates() == null ? 0 : response.getCandidates().size());

        if (pipelineResult.getCandidateIds() != null) {
            sessionManager.updateCandidates(sessionId, pipelineResult.getCandidateIds());
        }
        sessionManager.addHistory(sessionId, "Agent: " + response.getAnswer());

        transitionState(sessionId, turnId, currentFsmState, ConversationState.DONE);
        return response;
    }

    private void requireValidPlan(ExecutionPlan plan, TaskFamily taskFamily, String query) {
        if (plan != null && plan.getSteps() != null && !plan.getSteps().isEmpty()) {
            return;
        }
        throw new IllegalStateException("PLANNER_EMPTY_PLAN: taskFamily=" + taskFamily + ", query=" + truncate(query, 120));
    }

    private SessionState getOrCreateSession(ChatRequest request, String userId) {
        if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
            SessionState existing = sessionManager.getSession(request.getSessionId());
            if (existing != null) {
                return existing;
            }
        }
        return sessionManager.createSession(userId);
    }

    private String resolveTurnId(ChatRequest request) {
        if (request.getTurnId() != null && !request.getTurnId().isBlank()) {
            return request.getTurnId();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String resolveIdempotencyKey(ChatRequest request, String sessionId, String turnId) {
        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
            return request.getIdempotencyKey();
        }
        // 未传时退化为 session+turn，保持当前调用方兼容。
        return sessionId + ":" + turnId;
    }

    private String buildRequestHash(ChatRequest request) {
        String query = request.getQuery() == null ? "" : request.getQuery();
        String raw = request.getSessionId() + "|" + query + "|" + request.getUserId();
        return Integer.toHexString(raw.hashCode());
    }

    private boolean requiresPlanner(TaskFamily taskFamily) {
        return taskFamily == TaskFamily.COMPARE || taskFamily == TaskFamily.DEEP_DIVE;
    }

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

    private WorkflowDefinition buildDirectWorkflow(String taskFamily, String retrievalMode) {
        return WorkflowDefinition.builder()
                .id("DIRECT_QA_WORKFLOW")
                .name("直答工作流")
                .steps(List.of(
                        WorkflowStep.builder()
                                .stepId("direct-generate")
                                .capabilityName("llm_generate")
                                .parameters(Map.of(
                                        "taskFamily", taskFamily != null ? taskFamily : "QA",
                                        "retrievalMode", retrievalMode != null ? retrievalMode : "NONE"
                                ))
                                .build()
                ))
                .metadata(Map.of("type", "DIRECT"))
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

    private AgentResponse buildBudgetExhaustedResponse(SessionState sessionState, String turnId) {
        return AgentResponse.builder()
                .sessionId(sessionState.getSessionId())
                .turnId(turnId)
                .turnStatus(TurnStatus.FAILED.name())
                .errorCode("BUDGET_EXHAUSTED")
                .answer("抱歉，当前会话预算已耗尽，请创建新会话后继续。")
                .timestamp(LocalDateTime.now())
                .metadata(AgentResponse.ResponseMetadata.builder()
                        .remainingBudget(0)
                        .build())
                .build();
    }

    private AgentResponse buildClarificationResponse(SessionState sessionState, RouterResult routerResult, String turnId) {
        return AgentResponse.builder()
                .sessionId(sessionState.getSessionId())
                .turnId(turnId)
                .turnStatus(TurnStatus.DONE.name())
                .answer("我需要更多信息才能继续回答。")
                .needsClarification(true)
                .clarificationPrompt(routerResult.getClarificationQuestion())
                .timestamp(LocalDateTime.now())
                .build();
    }

    private AgentResponse buildWaitingResponse(SessionState sessionState, String turnId, String reason) {
        return AgentResponse.builder()
                .sessionId(sessionState.getSessionId())
                .turnId(turnId)
                .turnStatus(TurnStatus.DONE.name())
                .errorCode("NEED_USER_INPUT")
                .answer("需要补充信息后才能继续处理。")
                .needsClarification(true)
                .clarificationPrompt(reason)
                .timestamp(LocalDateTime.now())
                .build();
    }

    private AgentResponse buildBusyResponse(String sessionId, String turnId, String runningTurnId) {
        return AgentResponse.builder()
                .sessionId(sessionId)
                .turnId(turnId)
                .turnStatus(TurnStatus.BUSY.name())
                .errorCode("SESSION_BUSY")
                .runningTurnId(runningTurnId)
                .answer("当前会话正在处理中，请稍后重试。")
                .timestamp(LocalDateTime.now())
                .build();
    }

    private AgentResponse buildIdempotencyInProgressResponse(String sessionId,
                                                             String requestTurnId,
                                                             IdempotencyRecord record) {
        String processingTurnId = record.getTurnId() != null ? record.getTurnId() : requestTurnId;
        return AgentResponse.builder()
                .sessionId(sessionId)
                .turnId(processingTurnId)
                .turnStatus(TurnStatus.RUNNING.name())
                .errorCode("IDEMPOTENCY_IN_PROGRESS")
                .runningTurnId(processingTurnId)
                .answer("请求正在处理中，请稍后重试。")
                .timestamp(LocalDateTime.now())
                .build();
    }

    private AgentResponse replayFromIdempotencyRecord(String sessionId,
                                                      String requestTurnId,
                                                      IdempotencyRecord record) {
        if (record == null || record.getStatus() == null) {
            return null;
        }
        if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {
            return buildIdempotencyInProgressResponse(sessionId, requestTurnId, record);
        }
        AgentResponse snapshot = record.getResponseSnapshot();
        if (snapshot == null) {
            return null;
        }
        if (snapshot.getSessionId() == null || snapshot.getSessionId().isBlank()) {
            snapshot.setSessionId(sessionId);
        }
        if (snapshot.getTurnId() == null || snapshot.getTurnId().isBlank()) {
            snapshot.setTurnId(record.getTurnId() != null ? record.getTurnId() : requestTurnId);
        }
        if (snapshot.getTurnStatus() == null || snapshot.getTurnStatus().isBlank()) {
            snapshot.setTurnStatus(record.getStatus() == IdempotencyStatus.DONE
                    ? TurnStatus.DONE.name()
                    : TurnStatus.FAILED.name());
        }
        return snapshot;
    }

    private Map<String, Object> buildFilters(RouterResult routerResult) {
        if (routerResult == null || routerResult.getParams() == null) {
            return null;
        }
        Map<String, Object> params = routerResult.getParams();
        Map<String, Object> filters = new HashMap<>();
        putIfPresent(filters, "timeRange", params, "timeRange", "time_range", "time-range");
        putIfPresent(filters, "startDate", params, "startDate", "start_date");
        putIfPresent(filters, "endDate", params, "endDate", "end_date");
        putIfPresent(filters, "keywords", params, "keywords");
        putIfPresent(filters, "expandedKeywords", params, "expandedKeywords", "expanded_keywords");
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

    private void normalizeRetrievalMode(RouterResult routerResult) {
        if (routerResult == null) {
            return;
        }
        String intentScope = routerResult.getIntentScope();
        String retrievalMode = routerResult.getRetrievalMode();
        if (intentScope != null
                && "NEWS".equalsIgnoreCase(intentScope)
                && retrievalMode != null
                && !"NONE".equalsIgnoreCase(retrievalMode)
                && !"HYBRID".equalsIgnoreCase(retrievalMode)) {
            log.info("[router] 检索模式归一化: intentScope=NEWS, oldMode={}, newMode=HYBRID", retrievalMode);
            routerResult.setRetrievalMode("HYBRID");
        }
    }

    private ConversationState transitionState(String sessionId,
                                              String turnId,
                                              ConversationState current,
                                              ConversationState target) {
        if (sessionId == null || target == null) {
            return current;
        }
        ConversationState source = current != null ? current : getCurrentTurnFsmState(sessionId, turnId);
        try {
            conversationFSM.validateTransition(source, target);
            turnManager.updateFsmState(sessionId, turnId, target);
            log.info("[fsm] 状态迁移成功: sessionId={}, turnId={}, from={}, to={}",
                    sessionId, turnId, source, target);
            return target;
        } catch (Exception e) {
            turnManager.updateFsmState(sessionId, turnId, ConversationState.FAIL_SAFE);
            log.warn("[fsm] 状态迁移失败，进入 FAIL_SAFE: sessionId={}, turnId={}, from={}, to={}, error={}",
                    sessionId, turnId, source, target, e.getMessage());
            return ConversationState.FAIL_SAFE;
        }
    }

    private ConversationState getCurrentTurnFsmState(String sessionId, String turnId) {
        TurnState turnState = turnManager.getTurnState(sessionId, turnId);
        if (turnState == null || turnState.getFsmState() == null) {
            return ConversationState.START;
        }
        return turnState.getFsmState();
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
