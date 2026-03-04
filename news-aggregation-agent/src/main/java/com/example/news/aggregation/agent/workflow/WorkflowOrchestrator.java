package com.example.news.aggregation.agent.workflow;

import com.example.news.aggregation.agent.workflow.validation.SchemaValidationProperties;
import com.example.news.aggregation.agent.workflow.validation.ToolInputValidator;
import com.example.news.aggregation.agent.workflow.validation.ToolOutputValidator;
import com.example.news.aggregation.agent.workflow.validation.ToolValidationException;
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
import java.util.concurrent.CompletableFuture;

/**
 * 工作流编排器：负责执行显式 Workflow，或执行由 Planner 产出的 ExecutionPlan。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowOrchestrator {

    private static final String ATTR_RETRY_COUNTS = "workflow.retryCounts";

    /**
     * 任务类型与默认工具映射。
     */
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

    private final WorkflowRegistry workflowRegistry;
    private final ExecutionPlanWorkflowAdapter executionPlanWorkflowAdapter;
    private final DecisionTable decisionTable;
    private final ToolInputValidator toolInputValidator;
    private final ToolOutputValidator toolOutputValidator;
    private final SchemaValidationProperties schemaValidationProperties;

    /**
     * 执行 Planner 产出的 ExecutionPlan。
     */
    public WorkflowContext executePlan(ExecutionPlan plan, WorkflowContext context) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            log.warn("[workflow] 计划为空，跳过执行");
            return context;
        }
        String sessionId = context != null ? context.getSessionId() : "unknown";
        log.info("[workflow] 开始执行计划: sessionId={}, planId={}, stepCount={}",
                sessionId, plan.getPlanId(), plan.getSteps().size());

        if (context != null) {
            if (plan.getSchemaVersion() != null && !plan.getSchemaVersion().isBlank()) {
                context.putAttribute("workflow.schema.version", plan.getSchemaVersion());
            }
            if (plan.getSemanticVersion() != null && !plan.getSemanticVersion().isBlank()) {
                context.putAttribute("workflow.semantic.version", plan.getSemanticVersion());
            }
        }

        WorkflowDefinition workflow = executionPlanWorkflowAdapter.toWorkflowDefinition(plan, TYPE_TOOL_MAP);
        return executeWorkflow(workflow, context);
    }

    /**
     * 执行工作流定义。
     */
    public WorkflowContext executeWorkflow(WorkflowDefinition workflow, WorkflowContext context) {
        if (workflow == null || workflow.getSteps() == null || workflow.getSteps().isEmpty()) {
            log.warn("[workflow] 工作流为空，跳过执行");
            return context;
        }
        List<WorkflowStep> steps = workflow.getSteps();
        boolean hasDependencies = steps.stream()
                .anyMatch(step -> step != null && step.getDependsOn() != null && !step.getDependsOn().isEmpty());
        String sessionId = context != null ? context.getSessionId() : "unknown";
        log.info("[workflow] 执行工作流: sessionId={}, stepCount={}, hasDependencies={}",
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
            finalizeWorkflow(context, completed, failed);
            return context;
        }
        return executeWithDependencies(workflow, context);
    }

    /**
     * 根据 workflowId 执行内置工作流。
     */
    public WorkflowContext executeWorkflow(String workflowId, WorkflowContext context) {
        if (workflowId == null || workflowId.isBlank()) {
            log.warn("[workflow] workflowId 为空，跳过执行");
            return context;
        }
        String sessionId = context != null ? context.getSessionId() : "unknown";
        log.info("[workflow] 查找并执行工作流: sessionId={}, workflowId={}", sessionId, workflowId);
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
                log.warn("[workflow] 无可执行步骤，可能存在依赖环: sessionId={}, completed={}, failed={}",
                        context != null ? context.getSessionId() : "unknown", completed, failed);
                break;
            }

            log.info("[workflow] 依赖调度轮次: sessionId={}, round={}, readyStepCount={}, readySteps={}",
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

        finalizeWorkflow(context, completed, failed);
        return context;
    }

    private void finalizeWorkflow(WorkflowContext context, List<String> completed, List<String> failed) {
        if (context == null) {
            return;
        }
        context.putAttribute("workflow.completedSteps", completed);
        context.putAttribute("workflow.failedSteps", failed);
        context.putAttribute("workflow.status", failed.isEmpty() ? "SUCCESS" : "FAILED");
        log.info("[workflow] 执行结束: sessionId={}, completed={}, failed={}, status={}",
                context.getSessionId(), completed, failed, failed.isEmpty() ? "SUCCESS" : "FAILED");
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
            boolean allMet = deps.stream().allMatch(completed::contains);
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
        log.info("[workflow] 并行执行步骤: sessionId={}, stepCount={}, steps={}",
                context != null ? context.getSessionId() : "unknown",
                readySteps.size(),
                readySteps.stream().map(WorkflowStep::getStepId).toList());
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (WorkflowStep step : readySteps) {
            futures.add(CompletableFuture.runAsync(() -> executeStep(step, context, completed, failed, failFast)));
        }
        for (CompletableFuture<Void> future : futures) {
            try {
                future.join();
            } catch (Exception e) {
                log.warn("[workflow] 并行步骤执行异常: error={}", e.getMessage());
            }
        }
    }

    private void executeSequential(List<WorkflowStep> readySteps,
                                   WorkflowContext context,
                                   List<String> completed,
                                   List<String> failed,
                                   boolean failFast) {
        log.info("[workflow] 串行执行步骤: sessionId={}, stepCount={}, steps={}",
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
     * 执行单个步骤并在异常时进入决策表分流。
     */
    private void executeStep(WorkflowStep step,
                             WorkflowContext context,
                             List<String> completed,
                             List<String> failed,
                             boolean failFast) {
        try {
            String sessionId = context != null ? context.getSessionId() : "unknown";
            log.info("[workflow] 步骤开始: sessionId={}, stepId={}, capability={}, sideEffect={}, doneCheckRef={}",
                    sessionId, step.getStepId(), step.getCapabilityName(), step.getSideEffect(), step.getDoneCheckRef());
            toolInputValidator.validate(step, context);
            Object output = executeCapability(step.getCapabilityName(), step.getParameters(), context);
            toolOutputValidator.validate(step, context, output);
            completed.add(step.getStepId());
            clearRetryCount(context, step.getStepId());
            log.info("[workflow] 步骤完成: stepId={}, capability={}, sessionId={}",
                    step.getStepId(), step.getCapabilityName(), sessionId);
        } catch (Exception e) {
            DecisionResult decision = decideFailure(step, context, e);
            if (context != null) {
                context.putAttribute("workflow.lastDecision." + step.getStepId(), decision);
            }
            log.warn("[workflow] 步骤失败: stepId={}, action={}, reason={}, error={}",
                    step.getStepId(),
                    decision.getAction(),
                    decision.getReasonCode(),
                    e.getMessage());

            if (decision.getAction() == ExecutionEnums.DecisionAction.RETRY) {
                int retryCount = incrementRetryCount(context, step.getStepId());
                log.info("[workflow] 步骤重试: stepId={}, retryCount={}", step.getStepId(), retryCount);
                executeStep(step, context, completed, failed, failFast);
                return;
            }

            if (context == null) {
                failed.add(step.getStepId());
                return;
            }

            switch (decision.getAction()) {
                case WAIT -> {
                    context.putAttribute("workflow.waiting", true);
                    context.putAttribute("workflow.waiting.reason", decision.getReasonCode());
                    log.info("[workflow] 进入等待: stepId={}, reason={}", step.getStepId(), decision.getReasonCode());
                }
                case REPLAN -> {
                    context.putAttribute("workflow.replan.required", true);
                    log.info("[workflow] 标记重规划: stepId={}, reason={}", step.getStepId(), decision.getReasonCode());
                }
                case FALLBACK_TOOL -> {
                    List<String> fallbackTools = resolveEffectiveFallbackTools(step);
                    context.putAttribute("workflow.fallback.required", true);
                    context.putAttribute("workflow.fallback.tools." + step.getStepId(), fallbackTools);
                    if (!fallbackTools.isEmpty()) {
                        context.putAttribute("workflow.fallback.selected." + step.getStepId(), fallbackTools.get(0));
                    }
                    log.info("[workflow] 标记切换备选工具: stepId={}, reason={}, tools={}",
                            step.getStepId(), decision.getReasonCode(), fallbackTools);
                }
                case DEGRADE_OUTPUT -> {
                    context.putAttribute("workflow.degrade.required", true);
                    context.putAttribute("workflow.degrade.reason", decision.getReasonCode());
                    context.putAttribute("workflow.degrade.stepId", step.getStepId());
                    context.putAttribute("workflow.quality.gate", true);
                    if (!context.getAttributes().containsKey("answer")
                            || context.getAttributes().get("answer") == null
                            || String.valueOf(context.getAttributes().get("answer")).isBlank()) {
                        context.putAttribute("answer", "当前结果结构不完整，已自动降级为保守输出。");
                    }
                    completed.add(step.getStepId());
                    clearRetryCount(context, step.getStepId());
                    log.warn("[workflow] 执行降级输出: stepId={}, reason={}", step.getStepId(), decision.getReasonCode());
                    return;
                }
                case ABORT -> log.warn("[workflow] 终止步骤执行: stepId={}, reason={}", step.getStepId(), decision.getReasonCode());
                default -> {
                }
            }

            failed.add(step.getStepId());
            context.putAttribute("workflow.error", e.getMessage());
            if (failFast) {
                return;
            }
        }
    }

    private DecisionResult decideFailure(WorkflowStep step, WorkflowContext context, Exception e) {
        String reasonCode = resolveFailureReasonCode(e);
        FailureContext failureContext = FailureContext.builder()
                .errorCategory(resolveErrorCategory(e, reasonCode))
                .failureReasonCode(reasonCode)
                .retryCount(getRetryCount(context, step.getStepId()))
                .maxRetries(resolveMaxRetries(step))
                .hasFallbackTool(resolveHasFallback(step))
                .replanAllowed(resolveReplanAllowed(step))
                .needsExternalSignal(resolveNeedsExternalSignal(step, reasonCode))
                .sideEffect(resolveSideEffect(step))
                .effectState(resolveEffectState(step))
                .preferredResumeMode(resolvePreferredResumeMode(step))
                .build();
        DecisionResult decision = decisionTable.resolve(failureContext);
        log.info("[workflow] 失败决策: stepId={}, category={}, retry={}/{}, hasFallback={}, replanAllowed={}, action={}, nextState={}, reason={}",
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

    private ExecutionEnums.ErrorCategory resolveErrorCategory(Exception e, String reasonCode) {
        if (e instanceof ToolValidationException validationException
                && validationException.getErrorCategory() != null) {
            return validationException.getErrorCategory();
        }
        if ("missing_required_input".equals(reasonCode) || "need_user_clarification".equals(reasonCode)) {
            return ExecutionEnums.ErrorCategory.NEED_USER_INPUT;
        }
        if ("done_check_fail".equals(reasonCode)
                || "schema_version_unsupported_compat_restricted".equals(reasonCode)
                || "output_schema_version_mismatch".equals(reasonCode)) {
            return ExecutionEnums.ErrorCategory.QUALITY_FAIL;
        }
        if ("schema_version_unsupported".equals(reasonCode)) {
            return ExecutionEnums.ErrorCategory.POLICY_QUOTA_AUTH;
        }
        if ("output_missing_required_field_stable".equals(reasonCode)
                || "output_type_mismatch_stable".equals(reasonCode)
                || "output_parse_error".equals(reasonCode)
                || "output_malformed_temporary".equals(reasonCode)) {
            return ExecutionEnums.ErrorCategory.RETRYABLE_TOOL_ERROR;
        }
        String msg = e == null || e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (msg.contains("missing") || msg.contains("clarif") || msg.contains("required_input")) {
            return ExecutionEnums.ErrorCategory.NEED_USER_INPUT;
        }
        if (msg.contains("quota") || msg.contains("unauthorized") || msg.contains("forbidden") || msg.contains("policy")) {
            return ExecutionEnums.ErrorCategory.POLICY_QUOTA_AUTH;
        }
        return ExecutionEnums.ErrorCategory.RETRYABLE_TOOL_ERROR;
    }

    private String resolveFailureReasonCode(Exception e) {
        if (e instanceof ToolValidationException validationException
                && validationException.getReasonCode() != null
                && !validationException.getReasonCode().isBlank()) {
            return validationException.getReasonCode();
        }
        if (e == null || e.getMessage() == null) {
            return "unknown_error";
        }
        String msg = e.getMessage().toLowerCase();
        if (msg.contains("missing_required_input") || msg.contains("need_user_input")) {
            return "missing_required_input";
        }
        if (msg.contains("need_user_clarification")) {
            return "need_user_clarification";
        }
        if (msg.contains("schema_version_unsupported_compat_restricted")) {
            return "schema_version_unsupported_compat_restricted";
        }
        if (msg.contains("schema_version_unsupported")) {
            return "schema_version_unsupported";
        }
        if (msg.contains("output_schema_version_mismatch")) {
            return "output_schema_version_mismatch";
        }
        if (msg.contains("output_missing_required_field_stable")) {
            return "output_missing_required_field_stable";
        }
        if (msg.contains("output_type_mismatch_stable")) {
            return "output_type_mismatch_stable";
        }
        if (msg.contains("done_check_fail")) {
            return "done_check_fail";
        }
        if (msg.contains("parse")) {
            return "output_parse_error";
        }
        if (msg.contains("doctype") || msg.contains("<html")) {
            return "output_malformed_temporary";
        }
        if (msg.contains("schema") && msg.contains("version")) {
            return "output_schema_version_mismatch";
        }
        if (msg.contains("schema") || msg.contains("field")) {
            return "output_missing_required_field_stable";
        }
        return "runtime_error";
    }

    /**
     * 优先级：step.retryPolicy > tool-default > global-default。
     */
    private int resolveMaxRetries(WorkflowStep step) {
        if (step != null && step.getRetryPolicy() != null && step.getRetryPolicy().getMaxRetries() != null) {
            return Math.max(0, step.getRetryPolicy().getMaxRetries());
        }
        if (step != null
                && step.getCapabilityName() != null
                && schemaValidationProperties.getToolDefaultMaxRetries() != null) {
            Integer toolDefault = schemaValidationProperties.getToolDefaultMaxRetries().get(step.getCapabilityName());
            if (toolDefault != null) {
                return Math.max(0, toolDefault);
            }
        }
        Integer globalDefault = schemaValidationProperties.getGlobalMaxRetries();
        return globalDefault != null ? Math.max(0, globalDefault) : 2;
    }

    private boolean resolveHasFallback(WorkflowStep step) {
        return !resolveEffectiveFallbackTools(step).isEmpty();
    }

    /**
     * 优先级：step.failurePolicy.fallbackTools > tool-default > global-default。
     */
    private List<String> resolveEffectiveFallbackTools(WorkflowStep step) {
        if (step == null) {
            return List.of();
        }
        if (step.getFailurePolicy() != null
                && step.getFailurePolicy().getFallbackTools() != null
                && !step.getFailurePolicy().getFallbackTools().isEmpty()) {
            return step.getFailurePolicy().getFallbackTools();
        }
        if (step.getCapabilityName() != null
                && schemaValidationProperties.getToolDefaultFallbacks() != null) {
            List<String> toolDefault = schemaValidationProperties.getToolDefaultFallbacks().get(step.getCapabilityName());
            if (toolDefault != null && !toolDefault.isEmpty()) {
                return toolDefault;
            }
        }
        if (schemaValidationProperties.getGlobalFallbackTools() != null
                && !schemaValidationProperties.getGlobalFallbackTools().isEmpty()) {
            return schemaValidationProperties.getGlobalFallbackTools();
        }
        return List.of();
    }

    private boolean resolveReplanAllowed(WorkflowStep step) {
        if (step == null || step.getFailurePolicy() == null) {
            return true;
        }
        return step.getFailurePolicy().isReplanAllowed();
    }

    private boolean resolveNeedsExternalSignal(WorkflowStep step, String reasonCode) {
        if ("missing_required_input".equals(reasonCode) || "need_user_clarification".equals(reasonCode)) {
            return true;
        }
        if (step == null || step.getFailurePolicy() == null) {
            return false;
        }
        return step.getFailurePolicy().isNeedUserInputOnFailure();
    }

    private ExecutionEnums.ResumeMode resolvePreferredResumeMode(WorkflowStep step) {
        if (step == null || step.getFailurePolicy() == null || step.getFailurePolicy().getResumeMode() == null) {
            return ExecutionEnums.ResumeMode.CONTINUE;
        }
        return step.getFailurePolicy().getResumeMode();
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
        return value instanceof Boolean bool && bool;
    }

    private boolean isFailFast(WorkflowDefinition workflow) {
        if (workflow.getMetadata() == null) {
            return false;
        }
        Object value = workflow.getMetadata().get("failFast");
        return value instanceof Boolean bool && bool;
    }

    private Object executeCapability(String capabilityName, Map<String, Object> parameters, WorkflowContext context) {
        if (capabilityName == null || capabilityName.isBlank()) {
            log.warn("[workflow] capabilityName 为空，跳过执行");
            return null;
        }
        String sessionId = context != null ? context.getSessionId() : "unknown";
        log.info("[workflow] 执行能力: sessionId={}, capability={}", sessionId, capabilityName);

        CapabilityExecutor executor = workflowRegistry.getExecutor(capabilityName);
        if (executor == null) {
            log.warn("[workflow] 未找到能力执行器: capability={}", capabilityName);
            return null;
        }
        return executor.execute(parameters, context);
    }
}
