package com.example.news.aggregation.agent.service;

import com.example.news.aggregation.agent.client.PlannerClient;
import com.example.news.aggregation.agent.client.RouterClient;
import com.example.news.aggregation.agent.config.PlannerIntegrationProperties;
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
import com.example.news.aggregation.agent.execution.config.ExecutionPersistenceProperties;
import com.example.news.aggregation.agent.execution.domain.ExecutionRunEntity;
import com.example.news.aggregation.agent.execution.enums.RunStatus;
import com.example.news.aggregation.agent.execution.service.ExecutionPlanDigestService;
import com.example.news.aggregation.agent.execution.service.ExecutionEventService;
import com.example.news.aggregation.agent.execution.service.ExecutionRunService;
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
 * 对话统一编排入口。
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

    private final ExecutionRunService executionRunService;
    private final ExecutionPlanDigestService executionPlanDigestService;
    private final ExecutionEventService executionEventService;
    private final ExecutionPersistenceProperties executionPersistenceProperties;
    private final PlannerIntegrationProperties plannerIntegrationProperties;

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

        log.info("[turn] 接收请求|sessionId={} |turnId={} |idempotencyKey={} |query={}",
                sessionId, turnId, idempotencyKey, truncate(request.getQuery(), 160));

        IdempotencyRecord idempotencyRecord = turnManager.getIdempotencyRecord(sessionId, idempotencyKey);
        AgentResponse replay = replayFromIdempotencyRecord(sessionId, turnId, idempotencyRecord);
        if (replay != null) {
            return replay;
        }

        if (!sessionManager.tryAcquireSessionLock(sessionId, turnId)) {
            IdempotencyRecord retryRecord = turnManager.getIdempotencyRecord(sessionId, idempotencyKey);
            AgentResponse retryReplay = replayFromIdempotencyRecord(sessionId, turnId, retryRecord);
            if (retryReplay != null) {
                return retryReplay;
            }
            String runningTurnId = sessionManager.getRunningTurnId(sessionId);
            return buildBusyResponse(sessionId, turnId, runningTurnId);
        }

        try {
            boolean created = turnManager.tryCreateInProgressRecord(sessionId, idempotencyKey, turnId, requestHash);
            if (!created) {
                IdempotencyRecord existing = turnManager.getIdempotencyRecord(sessionId, idempotencyKey);
                AgentResponse existingReplay = replayFromIdempotencyRecord(sessionId, turnId, existing);
                if (existingReplay != null) {
                    return existingReplay;
                }
            }

            turnManager.createRunningTurn(sessionId, turnId, requestHash);
            AgentResponse response = executeSingleTurn(request, sessionState, turnId, requestHash);
            response.setSessionId(sessionId);
            response.setTurnId(turnId);
            if (response.getTurnStatus() == null || response.getTurnStatus().isBlank()) {
                response.setTurnStatus(TurnStatus.DONE.name());
            }

            if (TurnStatus.FAILED.name().equalsIgnoreCase(response.getTurnStatus())) {
                String errorCode = response.getErrorCode() != null ? response.getErrorCode() : "TURN_EXECUTION_FAILED";
                String errorMessage = response.getAnswer() != null ? response.getAnswer() : "turn failed";
                turnManager.markTurnFailed(sessionId, turnId, errorCode, errorMessage);
                turnManager.markIdempotencyFailed(sessionId, idempotencyKey, turnId, errorCode, errorMessage, response);
                return response;
            }

            turnManager.markTurnDone(sessionId, turnId, response);
            turnManager.markIdempotencyDone(sessionId, idempotencyKey, turnId, response);
            return response;
        } catch (Exception e) {
            log.error("[turn] 执行失败|sessionId={} |turnId={}", sessionId, turnId, e);
            AgentResponse failed = AgentResponse.builder()
                    .sessionId(sessionId)
                    .turnId(turnId)
                    .turnStatus(TurnStatus.FAILED.name())
                    .errorCode("TURN_EXECUTION_FAILED")
                    .answer("处理请求失败: " + e.getMessage())
                    .timestamp(LocalDateTime.now())
                    .build();
            turnManager.markTurnFailed(sessionId, turnId, failed.getErrorCode(), e.getMessage());
            turnManager.markIdempotencyFailed(sessionId, idempotencyKey, turnId, failed.getErrorCode(), e.getMessage(), failed);
            return failed;
        } finally {
            sessionManager.releaseSessionLock(sessionId, turnId);
        }
    }

    private AgentResponse executeSingleTurn(ChatRequest request,
                                            SessionState sessionState,
                                            String turnId,
                                            String requestHash) {
        String sessionId = sessionState.getSessionId();
        ConversationState currentFsmState = getCurrentTurnFsmState(sessionId, turnId);

        if (sessionState.isBudgetExhausted()) {
            return buildBudgetExhaustedResponse(sessionState, turnId);
        }

        sessionManager.addHistory(sessionId, "User: " + request.getQuery());
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
        currentFsmState = transitionState(sessionId, turnId, currentFsmState, nextAfterRoute);

        if (nextAfterRoute == ConversationState.NEED_CLARIFY) {
            transitionState(sessionId, turnId, currentFsmState, ConversationState.WAITING);
            return buildClarificationResponse(sessionState, routerResult, turnId);
        }

        WorkflowContext workflowContext = WorkflowContext.builder()
                .sessionId(sessionId)
                .query(request.getQuery())
                .queryInterpretation(routerResult.getQueryInterpretation())
                .taskFamily(routerResult.getTaskFamily())
                .workerId(executionPersistenceProperties.getPersistence().getWorkerId())
                .recoveryMode(false)
                .build();
        workflowContext.putAttribute("turnId", turnId);
        workflowContext.putAttribute("requestHash", requestHash);
        workflowContext.putAttribute("retrievalMode", routerResult.getRetrievalMode());
        workflowContext.putAttribute("tenantId", resolveTenantId(sessionState));

        Map<String, Object> filters = buildFilters(routerResult);
        if (filters != null && !filters.isEmpty()) {
            workflowContext.putAttribute("filters", filters);
        }

        if (nextAfterRoute == ConversationState.BUILD_EVIDENCE && directAnswer) {
            if (currentFsmState != ConversationState.BUILD_EVIDENCE) {
                currentFsmState = transitionState(sessionId, turnId, currentFsmState, ConversationState.BUILD_EVIDENCE);
            }
            WorkflowDefinition directWorkflow = buildDirectWorkflow(routerResult.getTaskFamily(), routerResult.getRetrievalMode());
            ExecutionRunService.RunAcquireResult acquireResult = bindRunForWorkflow(
                    workflowContext,
                    turnId,
                    requestHash,
                    directWorkflow.getId(),
                    workflowOrchestrator.computeWorkflowPlanHash(directWorkflow)
            );
            AgentResponse replayResponse = buildReplayRunResponseIfNeeded(sessionState, turnId, acquireResult);
            if (replayResponse != null) {
                return replayResponse;
            }
            workflowOrchestrator.executeWorkflow(directWorkflow, workflowContext);
        } else {
            PlannerIntegrationProperties.PlannerMode plannerMode = plannerIntegrationProperties.resolvePlannerMode();
            boolean usePlanner = shouldUsePlanner(taskFamily, directAnswer, plannerMode);
            log.info("[编排][规划] 规划策略决策|sessionId={} |turnId={} |taskFamily={} |directAnswer={} |plannerMode={} |usePlanner={}",
                    sessionId,
                    turnId,
                    taskFamily,
                    directAnswer,
                    plannerMode,
                    usePlanner);
            if (usePlanner) {
                if (currentFsmState != ConversationState.PLAN) {
                    currentFsmState = transitionState(sessionId, turnId, currentFsmState, ConversationState.PLAN);
                }
                Map<String, Object> plannerContext = buildPlannerContext(
                        sessionId,
                        turnId,
                        taskFamily,
                        routerResult.getRetrievalMode(),
                        plannerMode
                );
                ExecutionPlan plan = plannerClient.plan(request.getQuery(), routerResult, plannerContext);
                requireValidPlan(plan, taskFamily, request.getQuery());
                String plannerTraceId = extractPlannerTraceId(plan);
                if (plannerTraceId != null && !plannerTraceId.isBlank()) {
                    workflowContext.setPlannerTraceId(plannerTraceId);
                    workflowContext.putAttribute("workflow.planner.trace.id", plannerTraceId);
                }
                ExecutionRunService.RunAcquireResult acquireResult = bindRunForWorkflow(
                        workflowContext,
                        turnId,
                        requestHash,
                        plan.getPlanId(),
                        executionPlanDigestService.sha256Hex(plan)
                );
                recordPlannerTraceEvent(acquireResult, plannerTraceId, plannerMode);
                AgentResponse replayResponse = buildReplayRunResponseIfNeeded(sessionState, turnId, acquireResult);
                if (replayResponse != null) {
                    return replayResponse;
                }
                fsmContext.setExecutionPlan(plan);
                ConversationState nextAfterPlan = conversationFSM.nextState(ConversationState.PLAN, fsmContext);
                currentFsmState = transitionState(sessionId, turnId, currentFsmState, nextAfterPlan);
                workflowOrchestrator.executePlan(plan, workflowContext);
            } else {
                if (currentFsmState != ConversationState.RETRIEVE) {
                    currentFsmState = transitionState(sessionId, turnId, currentFsmState, ConversationState.RETRIEVE);
                }
                String workflowId = resolveWorkflowId(taskFamily);
                if (!workflowOrchestrator.hasWorkflow(workflowId)) {
                    throw new IllegalStateException("WORKFLOW_NOT_FOUND:" + workflowId + ", taskFamily=" + taskFamily);
                }
                ExecutionRunService.RunAcquireResult acquireResult = bindRunForWorkflow(
                        workflowContext,
                        turnId,
                        requestHash,
                        workflowId,
                        workflowOrchestrator.computeWorkflowPlanHash(workflowId)
                );
                AgentResponse replayResponse = buildReplayRunResponseIfNeeded(sessionState, turnId, acquireResult);
                if (replayResponse != null) {
                    return replayResponse;
                }
                workflowOrchestrator.executeWorkflow(workflowId, workflowContext);
            }
        }

        if (Boolean.TRUE.equals(workflowContext.getAttributes().get("workflow.waiting"))) {
            String waitingReason = String.valueOf(workflowContext.getAttributes()
                    .getOrDefault("workflow.waiting.reason", "需要更多输入信息"));
            transitionState(sessionId, turnId, currentFsmState, ConversationState.WAITING);
            return buildWaitingResponse(sessionState, turnId, waitingReason);
        }

        fsmContext.setEvidenceCount(workflowContext.getEvidence().size());
        if (currentFsmState != ConversationState.BUILD_EVIDENCE) {
            currentFsmState = transitionState(sessionId, turnId, currentFsmState, ConversationState.BUILD_EVIDENCE);
        }

        ConversationState nextAfterEvidence = conversationFSM.nextState(ConversationState.BUILD_EVIDENCE, fsmContext);
        currentFsmState = transitionState(sessionId, turnId, currentFsmState, nextAfterEvidence);
        if (nextAfterEvidence == ConversationState.GENERATE && currentFsmState != ConversationState.VALIDATE) {
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

        if (pipelineResult.getCandidateIds() != null) {
            sessionManager.updateCandidates(sessionId, pipelineResult.getCandidateIds());
        }
        sessionManager.addHistory(sessionId, "Agent: " + response.getAnswer());

        transitionState(sessionId, turnId, currentFsmState, ConversationState.DONE);
        return response;
    }

    private ExecutionRunService.RunAcquireResult bindRunForWorkflow(WorkflowContext workflowContext,
                                                                    String turnId,
                                                                    String requestHash,
                                                                    String planId,
                                                                    String planHash) {
        String sessionId = workflowContext.getSessionId();
        Object tenantObj = workflowContext.getAttributes().get("tenantId");
        String tenantId = tenantObj == null ? "default" : String.valueOf(tenantObj);
        String requestDedupeKey = executionRunService.buildRequestDedupeKey(sessionId, turnId, requestHash);
        if (planHash == null || planHash.isBlank()) {
            throw new IllegalStateException("PLAN_HASH_REQUIRED: planId=" + planId);
        }
        String effectivePlanHash = planHash;

        ExecutionRunService.RunAcquireResult acquireResult = executionRunService.createOrReplayRunWithFlag(
                tenantId,
                sessionId,
                turnId,
                requestDedupeKey,
                effectivePlanHash,
                planId
        );
        ExecutionRunEntity run = acquireResult.run();
        workflowContext.setRunId(run.getRunId());
        workflowContext.putAttribute("workflow.request.dedupe.key", requestDedupeKey);
        workflowContext.putAttribute("workflow.plan.hash", effectivePlanHash);
        workflowContext.putAttribute("workflow.run.replayed", acquireResult.replayed());
        workflowContext.putAttribute("workflow.run.status", run.getStatus());
        workflowContext.putAttribute("workflow.run.tenantId", run.getTenantId());
        turnManager.bindRunId(sessionId, turnId, run.getRunId());
        return acquireResult;
    }

    private String resolveTenantId(SessionState sessionState) {
        if (sessionState == null || sessionState.getUserId() == null || sessionState.getUserId().isBlank()) {
            return "default";
        }
        return sessionState.getUserId().trim();
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
        return sessionId + ":" + turnId;
    }

    private String buildRequestHash(ChatRequest request) {
        String query = request.getQuery() == null ? "" : request.getQuery();
        String sessionId = request.getSessionId() == null ? "" : request.getSessionId();
        String userId = request.getUserId() == null ? "" : request.getUserId();
        String turnId = request.getTurnId() == null ? "" : request.getTurnId();
        String raw = sessionId + "|" + turnId + "|" + userId + "|" + query;
        return executionRunService.sha256Hex(raw);
    }

    /**
     * Planner 策略：所有非直答任务都走 Planner 规划。
     */
    private boolean shouldUsePlanner(TaskFamily taskFamily,
                                     boolean directAnswer,
                                     PlannerIntegrationProperties.PlannerMode plannerMode) {
        if (directAnswer) {
            return false;
        }
        return true;
    }

    /**
     * 构建 planner 上下文：用于链路追踪和策略可观测，不改变执行期 selector/circuit/fallback 语义。
     */
    private Map<String, Object> buildPlannerContext(String sessionId,
                                                    String turnId,
                                                    TaskFamily taskFamily,
                                                    String retrievalMode,
                                                    PlannerIntegrationProperties.PlannerMode plannerMode) {
        Map<String, Object> context = new HashMap<>();
        context.put("plannerMode", plannerMode == null ? "HYBRID" : plannerMode.name());
        context.put("toolBindingMode", plannerIntegrationProperties.resolveToolBindingMode());
        context.put("sessionId", sessionId);
        context.put("turnId", turnId);
        context.put("taskFamily", taskFamily == null ? null : taskFamily.name());
        context.put("retrievalMode", retrievalMode);
        context.put("plannerTraceId", UUID.randomUUID().toString().replace("-", ""));
        return context;
    }

    /**
     * 从计划元数据中提取 traceId，优先使用 planner 输出，保证回放可以串联到 planner 决策。
     */
    private String extractPlannerTraceId(ExecutionPlan plan) {
        if (plan == null) {
            return null;
        }
        String traceId = plan.getPlannerTraceId();
        if (traceId == null || traceId.isBlank()) {
            return null;
        }
        return traceId.trim();
    }

    /**
     * 将 planner trace 与 run 进行绑定，便于 replay 直接定位规划链路。
     */
    private void recordPlannerTraceEvent(ExecutionRunService.RunAcquireResult acquireResult,
                                         String plannerTraceId,
                                         PlannerIntegrationProperties.PlannerMode plannerMode) {
        if (acquireResult == null || acquireResult.run() == null || plannerTraceId == null || plannerTraceId.isBlank()) {
            return;
        }
        String payloadJson = "{\"plannerTraceId\":\"" + plannerTraceId + "\",\"plannerMode\":\""
                + (plannerMode == null ? "HYBRID" : plannerMode.name()) + "\"}";
        executionEventService.record(
                acquireResult.run().getRunId(),
                null,
                "RUN_PLANNER_TRACE_BOUND",
                acquireResult.run().getStatus(),
                acquireResult.run().getStatus(),
                "planner_trace_bound",
                "planner trace id 已绑定到执行 run",
                payloadJson
        );
        log.info("[编排][规划] plannerTraceId 已写入运行事件|runId={} |plannerTraceId={} |plannerMode={}",
                acquireResult.run().getRunId(),
                plannerTraceId,
                plannerMode == null ? "HYBRID" : plannerMode.name());
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
        Map<String, Object> extraData = buildExecutionObservabilityData(workflowContext);

        return PipelineResult.builder()
                .answer(answer)
                .candidateIds(candidateIds)
                .citations(citations)
                .llmCallCount(answer.isEmpty() ? 0 : 1)
                .executionTimeMs(0L)
                .success(true)
                .extraData(extraData)
                .build();
    }

    private Map<String, Object> buildExecutionObservabilityData(WorkflowContext workflowContext) {
        Map<String, Object> data = new HashMap<>();
        if (workflowContext == null || workflowContext.getAttributes() == null) {
            return data;
        }
        Map<String, Object> attrs = workflowContext.getAttributes();
        data.put("qualityGateTriggered", attrs.getOrDefault("workflow.quality.gate", false));
        data.put("qualityWarningCount", attrs.getOrDefault("workflow.quality.warning.count", 0));
        data.put("qualityWarnings", attrs.getOrDefault("workflow.quality.warnings", List.of()));
        data.put("schemaValidationMode", attrs.getOrDefault("workflow.schema.validation.mode", ""));
        data.put("executionSchemaVersion", attrs.getOrDefault("workflow.schema.version", ""));
        data.put("executionSemanticVersion", attrs.getOrDefault("workflow.semantic.version", ""));
        data.put("inputValidationCount", attrs.getOrDefault("workflow.validation.input.checked.count", 0));
        data.put("outputValidationCount", attrs.getOrDefault("workflow.validation.output.checked.count", 0));
        data.put("degradeOutputTriggered", attrs.getOrDefault("workflow.degrade.required", false));
        data.put("degradeReasonCode", attrs.getOrDefault("workflow.degrade.reason", ""));
        data.put("degradeStepId", attrs.getOrDefault("workflow.degrade.stepId", ""));
        data.put("plannerTraceId", attrs.getOrDefault("workflow.planner.trace.id", ""));

        data.put("executionRunId", workflowContext.getRunId());
        ExecutionRunEntity run = workflowContext.getRunId() == null ? null : executionRunService.findByRunId(workflowContext.getRunId());
        data.put("executionRunStatus", run != null ? run.getStatus() : "");
        data.put("currentExecutionStep", run != null ? run.getCurrentStep() : "");
        data.put("effectLatchStatus", attrs.getOrDefault("workflow.effect.status", ""));
        data.put("executionStepAttempt", attrs.getOrDefault("workflow.execution.attempt", 0));
        data.put("executionReasonCode", attrs.getOrDefault("workflow.execution.reason", ""));
        return data;
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
                .metadata(AgentResponse.ResponseMetadata.builder().remainingBudget(0).build())
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

    private AgentResponse buildReplayRunResponseIfNeeded(SessionState sessionState,
                                                         String turnId,
                                                         ExecutionRunService.RunAcquireResult acquireResult) {
        if (acquireResult == null || !acquireResult.replayed() || acquireResult.run() == null) {
            return null;
        }
        ExecutionRunEntity run = acquireResult.run();
        String runStatus = run.getStatus();
        if (RunStatus.RUNNING.name().equals(runStatus) || RunStatus.PENDING.name().equals(runStatus)) {
            log.info("[turn] 复用运行中run，直接返回进行中状态|sessionId={} |turnId={} |runId={} |status={}",
                    sessionState.getSessionId(), turnId, run.getRunId(), runStatus);
            return buildRunExecutingResponse(sessionState.getSessionId(), turnId, run.getRunId());
        }
        if (RunStatus.WAITING.name().equals(runStatus)) {
            String waitingReason = run.getErrorCode() == null || run.getErrorCode().isBlank()
                    ? "需要更多输入信息"
                    : run.getErrorCode();
            log.info("[turn] 复用WAITING run，直接返回等待状态|sessionId={} |turnId={} |runId={} |reason={}",
                    sessionState.getSessionId(), turnId, run.getRunId(), waitingReason);
            return buildWaitingResponse(sessionState, turnId, waitingReason);
        }
        return null;
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

    private AgentResponse buildRunExecutingResponse(String sessionId, String turnId, String runId) {
        return AgentResponse.builder()
                .sessionId(sessionId)
                .turnId(turnId)
                .turnStatus(TurnStatus.RUNNING.name())
                .errorCode("RUN_IN_PROGRESS")
                .runningTurnId(turnId)
                .answer("请求正在处理中，请稍后重试。")
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
            return target;
        } catch (Exception e) {
            turnManager.updateFsmState(sessionId, turnId, ConversationState.FAIL_SAFE);
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
