package com.example.news.aggregation.agent.workflow;

import com.example.news.aggregation.llm.springai.contract.ExecutionEnums;
import com.example.news.aggregation.llm.springai.contract.ExecutionPlan;
import com.example.news.aggregation.llm.springai.decision.DecisionResult;
import com.example.news.aggregation.llm.springai.decision.DecisionTable;
import com.example.news.aggregation.llm.springai.decision.FailureContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流编排器：执行显式 Workflow 或 Planner 输出的 Plan。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowOrchestrator {

    private final WorkflowRegistry workflowRegistry;
    private final ExecutionPlanWorkflowAdapter executionPlanWorkflowAdapter;
    private final DecisionTable decisionTable;

    /** 任务类型到默认能力列表的映射。 */
    private static final Map<String, List<String>> TYPE_TOOL_MAP = Map.of(
            "SEARCH", List.of("search_news"),
            "RETRIEVE", List.of("retrieve_news"),
            "QA", List.of("llm_generate"),
            "SUMMARIZE", List.of("llm_generate"),
            "COMPARE", List.of("llm_generate"),
            "ANALYZE", List.of("llm_generate"),
            "TIMELINE", List.of("llm_generate"),
            "DEEP_DIVE", List.of("llm_generate")
    );
    private static final String ATTR_RETRY_COUNTS = "workflow.retryCounts";

    /**
     * 执行 Planner 产出的 ExecutionPlan。
     * 该方法负责将计划转换为统一工作流定义，然后复用工作流执行主流程。
     */
    public WorkflowContext executePlan(ExecutionPlan plan, WorkflowContext context) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            log.warn("[workflow] 执行计划失败：计划为空，跳过执行");
            return context;
        }
        String sessionId = context != null ? context.getSessionId() : "unknown";
        int taskCount = plan.getSteps() != null ? plan.getSteps().size() : 0;
        log.info("[workflow] 执行计划FLOW|agent|workflow=plan|step=start|sessionId={}|taskCount={}|next=转换为WorkflowDefinition",
                sessionId, taskCount);

        // 调用点注释：将 Planner 的任务列表转换成可执行 Workflow。
        WorkflowDefinition workflow = executionPlanWorkflowAdapter.toWorkflowDefinition(plan, TYPE_TOOL_MAP);
        return executeWorkflow(workflow, context);
    }

    /**
     * 执行工作流定义。
     * 当步骤没有依赖关系时，按线性顺序执行；有依赖关系时，进入依赖调度执行。
     */
    public WorkflowContext executeWorkflow(WorkflowDefinition workflow, WorkflowContext context) {
        if (workflow == null || workflow.getSteps() == null || workflow.getSteps().isEmpty()) {
            log.warn("[workflow] 执行工作流失败：工作流为空，跳过执行");
            return context;
        }
        List<WorkflowStep> steps = workflow.getSteps();
        boolean hasDependencies = steps.stream().anyMatch(step ->
                step != null && step.getDependsOn() != null && !step.getDependsOn().isEmpty());
        String sessionId = context != null ? context.getSessionId() : "unknown";
        log.info("[workflow] 执行工作流FLOW|agent|workflow=explicit|step=start|sessionId={}|stepCount={}|hasDependencies={}|next=执行能力节点",
                sessionId, steps.size(), hasDependencies);

        if (!hasDependencies) {
            boolean failFast = isFailFast(workflow);
            List<String> completed = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            for (WorkflowStep step : steps) {
                if (step == null) {
                    continue;
                }
                executeStep(step, context, completed, failed, failFast);
                if (failFast && !failed.isEmpty()) {
                    break;
                }
            }
            context.putAttribute("workflow.completedSteps", completed);
            context.putAttribute("workflow.failedSteps", failed);
            context.putAttribute("workflow.status", failed.isEmpty() ? "SUCCESS" : "FAILED");
            log.info("[workflow] 工作流执行结束：sessionId={}, completed={}, failed={}, status={}",
                    sessionId, completed, failed, failed.isEmpty() ? "SUCCESS" : "FAILED");
            return context;
        }

        return executeWithDependencies(workflow, context);
    }

    /**
     * 根据 workflowId 查找并执行内置工作流。
     */
    public WorkflowContext executeWorkflow(String workflowId, WorkflowContext context) {
        if (workflowId == null || workflowId.isBlank()) {
            log.warn("[workflow] workflowId 为空，跳过执行");
            return context;
        }
        String sessionId = context != null ? context.getSessionId() : "unknown";
        log.info("[workflow] 指定工作流FLOW|agent|workflow=explicit|step=lookup|sessionId={}|workflowId={}|next=执行工作流",
                sessionId, workflowId);

        // 调用点注释：从注册表读取内置工作流定义（如 QA_WORKFLOW）。
        WorkflowDefinition workflow = workflowRegistry.getWorkflow(workflowId);
        return executeWorkflow(workflow, context);
    }

    public boolean hasWorkflow(String workflowId) {
        return workflowRegistry.containsWorkflow(workflowId);
    }

    private WorkflowContext executeWithDependencies(WorkflowDefinition workflow, WorkflowContext context) {
        List<WorkflowStep> steps = workflow.getSteps();
        Map<String, WorkflowStep> stepMap = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            WorkflowStep step = steps.get(i);
            if (step == null) {
                continue;
            }
            if (step.getStepId() == null || step.getStepId().isBlank()) {
                step.setStepId("step-" + i);
            }
            stepMap.put(step.getStepId(), step);
        }

        boolean parallelizable = isParallelizable(workflow);
        boolean failFast = isFailFast(workflow);

        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int round = 0;

        while (completed.size() + failed.size() < stepMap.size()) {
            round++;
            List<WorkflowStep> readySteps = findReadySteps(stepMap, completed, failed);
            if (readySteps.isEmpty()) {
                log.warn("[workflow] 无可执行步骤，可能存在依赖环：sessionId={}, completed={}, failed={}",
                        context != null ? context.getSessionId() : "unknown", completed, failed);
                break;
            }
            log.info("[workflow] 依赖调度轮次：sessionId={}, round={}, readyStepCount={}, readySteps={}",
                    context != null ? context.getSessionId() : "unknown",
                    round,
                    readySteps.size(),
                    readySteps.stream().map(WorkflowStep::getStepId).toList());

            if (parallelizable) {
                executeParallel(readySteps, context, completed, failed, failFast);
            } else {
                executeSequential(readySteps, context, completed, failed, failFast);
            }

            if (failFast && !failed.isEmpty()) {
                break;
            }
        }

        context.putAttribute("workflow.completedSteps", completed);
        context.putAttribute("workflow.failedSteps", failed);
        context.putAttribute("workflow.status", failed.isEmpty() ? "SUCCESS" : "FAILED");
        log.info("[workflow] 依赖工作流执行结束：sessionId={}, completed={}, failed={}, status={}",
                context != null ? context.getSessionId() : "unknown",
                completed,
                failed,
                failed.isEmpty() ? "SUCCESS" : "FAILED");
        return context;
    }

    private List<WorkflowStep> findReadySteps(Map<String, WorkflowStep> stepMap,
                                              List<String> completed,
                                              List<String> failed) {
        List<WorkflowStep> ready = new ArrayList<>();
        for (WorkflowStep step : stepMap.values()) {
            if (step == null) {
                continue;
            }
            String stepId = step.getStepId();
            if (completed.contains(stepId) || failed.contains(stepId)) {
                continue;
            }
            List<String> deps = step.getDependsOn();
            if (deps == null || deps.isEmpty()) {
                ready.add(step);
                continue;
            }
            boolean allMet = true;
            for (String dep : deps) {
                if (!completed.contains(dep)) {
                    allMet = false;
                    break;
                }
            }
            if (allMet) {
                ready.add(step);
            }
        }
        return ready;
    }

    private void executeParallel(List<WorkflowStep> readySteps,
                                 WorkflowContext context,
                                 List<String> completed,
                                 List<String> failed,
                                 boolean failFast) {
        List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
        log.info("[workflow] 并行执行步骤：sessionId={}, stepCount={}, steps={}",
                context != null ? context.getSessionId() : "unknown",
                readySteps.size(),
                readySteps.stream().map(WorkflowStep::getStepId).toList());
        for (WorkflowStep step : readySteps) {
            futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                executeStep(step, context, completed, failed, failFast);
            }));
        }
        for (java.util.concurrent.CompletableFuture<Void> future : futures) {
            try {
                future.join();
            } catch (Exception e) {
                log.warn("[workflow] 并行步骤执行异常：error={}", e.getMessage());
            }
        }
    }

    private void executeSequential(List<WorkflowStep> readySteps,
                                   WorkflowContext context,
                                   List<String> completed,
                                   List<String> failed,
                                   boolean failFast) {
        log.info("[workflow] 串行执行步骤：sessionId={}, stepCount={}, steps={}",
                context != null ? context.getSessionId() : "unknown",
                readySteps.size(),
                readySteps.stream().map(WorkflowStep::getStepId).toList());
        for (WorkflowStep step : readySteps) {
            executeStep(step, context, completed, failed, failFast);
            if (failFast && !failed.isEmpty()) {
                break;
            }
        }
    }

    /**
     * 执行单个步骤，并在失败时走决策表分流。
     * 分流动作包括：重试、等待、重规划、切换备选工具、终止。
     */
    private void executeStep(WorkflowStep step,
                             WorkflowContext context,
                             List<String> completed,
                             List<String> failed,
                             boolean failFast) {
        try {
            String sessionId = context != null ? context.getSessionId() : "unknown";
            log.info("[workflow] 步骤开始：sessionId={}, stepId={}, capability={}, sideEffect={}, doneCheckRef={}",
                    sessionId, step.getStepId(), step.getCapabilityName(), step.getSideEffect(), step.getDoneCheckRef());
            executeCapability(step.getCapabilityName(), step.getParameters(), context);
            completed.add(step.getStepId());
            clearRetryCount(context, step.getStepId());
            log.info("[workflow] 步骤完成FLOW|agent|workflow=step|stepId={}|capability={}|sessionId={}|next=依赖判断/下一步",
                    step.getStepId(), step.getCapabilityName(), sessionId);
        } catch (Exception e) {
            DecisionResult decision = decideFailure(step, context, e);
            context.putAttribute("workflow.lastDecision." + step.getStepId(), decision);
            log.warn("[workflow] 步骤失败：stepId={}, action={}, reason={}, error={}",
                    step.getStepId(),
                    decision.getAction(),
                    decision.getReasonCode(),
                    e.getMessage());

            if (decision.getAction() == ExecutionEnums.DecisionAction.RETRY) {
                int retryCount = incrementRetryCount(context, step.getStepId());
                log.info("[workflow] 重试步骤FLOW|agent|workflow=step|stepId={}|retryCount={}|next=重新执行",
                        step.getStepId(), retryCount);
                executeStep(step, context, completed, failed, failFast);
                return;
            }

            if (decision.getAction() == ExecutionEnums.DecisionAction.WAIT) {
                context.putAttribute("workflow.waiting", true);
                context.putAttribute("workflow.waiting.reason", decision.getReasonCode());
                log.info("[workflow] 进入等待：stepId={}, reason={}", step.getStepId(), decision.getReasonCode());
            } else if (decision.getAction() == ExecutionEnums.DecisionAction.REPLAN) {
                context.putAttribute("workflow.replan.required", true);
                log.info("[workflow] 标记重规划：stepId={}, reason={}", step.getStepId(), decision.getReasonCode());
            } else if (decision.getAction() == ExecutionEnums.DecisionAction.FALLBACK_TOOL) {
                context.putAttribute("workflow.fallback.required", true);
                log.info("[workflow] 标记切换备选工具：stepId={}, reason={}", step.getStepId(), decision.getReasonCode());
            } else if (decision.getAction() == ExecutionEnums.DecisionAction.ABORT) {
                log.warn("[workflow] 终止步骤执行：stepId={}, reason={}", step.getStepId(), decision.getReasonCode());
            }

            failed.add(step.getStepId());
            context.putAttribute("workflow.error", e.getMessage());
            if (failFast) {
                return;
            }
        }
    }

    private DecisionResult decideFailure(WorkflowStep step, WorkflowContext context, Exception e) {
        FailureContext failureContext = FailureContext.builder()
                .errorCategory(resolveErrorCategory(e))
                .retryCount(getRetryCount(context, step.getStepId()))
                .maxRetries(resolveMaxRetries(step))
                .hasFallbackTool(resolveHasFallback(step))
                .replanAllowed(true)
                .needsExternalSignal(false)
                .sideEffect(resolveSideEffect(step))
                .effectState(resolveEffectState(step))
                .preferredResumeMode(ExecutionEnums.ResumeMode.CONTINUE)
                .build();
        DecisionResult decision = decisionTable.resolve(failureContext);
        log.info("[workflow] 失败决策：stepId={}, category={}, retry={}/{}, hasFallback={}, replanAllowed={}, action={}, nextState={}, reason={}",
                step != null ? step.getStepId() : "unknown",
                failureContext.getErrorCategory(),
                failureContext.getRetryCount(),
                failureContext.getMaxRetries(),
                failureContext.isHasFallbackTool(),
                failureContext.isReplanAllowed(),
                decision.getAction(),
                decision.getNextState(),
                decision.getReasonCode());
        return decision;
    }

    private ExecutionEnums.ErrorCategory resolveErrorCategory(Exception e) {
        if (e == null || e.getMessage() == null) {
            return ExecutionEnums.ErrorCategory.RETRYABLE_TOOL_ERROR;
        }
        String msg = e.getMessage().toLowerCase();
        if (msg.contains("missing") || msg.contains("clarif") || msg.contains("required_input")) {
            return ExecutionEnums.ErrorCategory.NEED_USER_INPUT;
        }
        if (msg.contains("quota") || msg.contains("unauthorized") || msg.contains("forbidden") || msg.contains("policy")) {
            return ExecutionEnums.ErrorCategory.POLICY_QUOTA_AUTH;
        }
        return ExecutionEnums.ErrorCategory.RETRYABLE_TOOL_ERROR;
    }

    private int resolveMaxRetries(WorkflowStep step) {
        if (step == null || step.getParameters() == null) {
            return 2;
        }
        Object value = step.getParameters().get("maxRetries");
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        return 2;
    }

    private boolean resolveHasFallback(WorkflowStep step) {
        if (step == null || step.getParameters() == null) {
            return false;
        }
        Object fallback = step.getParameters().get("fallbackTools");
        return fallback instanceof List<?> list && !list.isEmpty();
    }

    private ExecutionEnums.SideEffectType resolveSideEffect(WorkflowStep step) {
        if (step == null || step.getSideEffect() == null || step.getSideEffect().isBlank()) {
            return ExecutionEnums.SideEffectType.NONE;
        }
        try {
            return ExecutionEnums.SideEffectType.valueOf(step.getSideEffect());
        } catch (Exception ignore) {
            return ExecutionEnums.SideEffectType.NONE;
        }
    }

    private ExecutionEnums.EffectState resolveEffectState(WorkflowStep step) {
        ExecutionEnums.SideEffectType sideEffectType = resolveSideEffect(step);
        if (sideEffectType == ExecutionEnums.SideEffectType.WRITE
                || sideEffectType == ExecutionEnums.SideEffectType.EXTERNAL) {
            return ExecutionEnums.EffectState.UNKNOWN;
        }
        return ExecutionEnums.EffectState.NOT_APPLIED;
    }

    @SuppressWarnings("unchecked")
    private int getRetryCount(WorkflowContext context, String stepId) {
        if (context == null || stepId == null) {
            return 0;
        }
        Object raw = context.getAttributes().get(ATTR_RETRY_COUNTS);
        if (!(raw instanceof Map<?, ?>)) {
            return 0;
        }
        Map<String, Integer> retryCounts = (Map<String, Integer>) raw;
        return retryCounts.getOrDefault(stepId, 0);
    }

    @SuppressWarnings("unchecked")
    private int incrementRetryCount(WorkflowContext context, String stepId) {
        if (context == null || stepId == null) {
            return 0;
        }
        Object raw = context.getAttributes().get(ATTR_RETRY_COUNTS);
        Map<String, Integer> retryCounts;
        if (raw instanceof Map<?, ?> map) {
            retryCounts = (Map<String, Integer>) map;
        } else {
            retryCounts = new HashMap<>();
            context.putAttribute(ATTR_RETRY_COUNTS, retryCounts);
        }
        int next = retryCounts.getOrDefault(stepId, 0) + 1;
        retryCounts.put(stepId, next);
        return next;
    }

    @SuppressWarnings("unchecked")
    private void clearRetryCount(WorkflowContext context, String stepId) {
        if (context == null || stepId == null) {
            return;
        }
        Object raw = context.getAttributes().get(ATTR_RETRY_COUNTS);
        if (!(raw instanceof Map<?, ?>)) {
            return;
        }
        Map<String, Integer> retryCounts = (Map<String, Integer>) raw;
        retryCounts.remove(stepId);
    }

    private boolean isParallelizable(WorkflowDefinition workflow) {
        if (workflow.getMetadata() == null) {
            return false;
        }
        Object value = workflow.getMetadata().get("parallelizable");
        return value instanceof Boolean && (Boolean) value;
    }

    private boolean isFailFast(WorkflowDefinition workflow) {
        if (workflow.getMetadata() == null) {
            return false;
        }
        Object value = workflow.getMetadata().get("failFast");
        return value instanceof Boolean && (Boolean) value;
    }

    private void executeCapability(String capabilityName, Map<String, Object> parameters, WorkflowContext context) {
        if (capabilityName == null || capabilityName.isBlank()) {
            log.warn("[workflow] capabilityName 为空，跳过执行");
            return;
        }
        String sessionId = context != null ? context.getSessionId() : "unknown";
        log.info("[workflow] 执行能力FLOW|agent|workflow=capability|step=start|sessionId={}|capability={}|next=对应执行器",
                sessionId, capabilityName);

        // 调用点注释：按 capabilityName 解析执行器，retrieve_news 会映射到 RetrieveNewsExecutor。
        CapabilityExecutor executor = workflowRegistry.getExecutor(capabilityName);
        if (executor == null) {
            log.warn("[workflow] 未找到能力执行器：capability={}", capabilityName);
            return;
        }

        // 调用点注释：真正执行节点逻辑；retrieve_news 在这里调用检索客户端。
        executor.execute(parameters, context);
    }
}
