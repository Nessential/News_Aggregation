package com.example.news.aggregation.agent.workflow;

import com.example.news.aggregation.agent.execution.config.ExecutionPersistenceProperties;
import com.example.news.aggregation.agent.execution.config.ReplanControlProperties;
import com.example.news.aggregation.agent.execution.domain.ExecutionEffectLatchEntity;
import com.example.news.aggregation.agent.execution.domain.ExecutionRunEntity;
import com.example.news.aggregation.agent.execution.domain.ExecutionStepRunEntity;
import com.example.news.aggregation.agent.client.LlmMetricsContext;
import com.example.news.aggregation.agent.client.RetrievalMetricsContext;
import com.example.news.aggregation.agent.execution.enums.EffectStatus;
import com.example.news.aggregation.agent.execution.enums.RunStatus;
import com.example.news.aggregation.agent.execution.enums.StepStatus;
import com.example.news.aggregation.agent.execution.model.ReplanChangeProof;
import com.example.news.aggregation.agent.execution.model.ReplanEvidenceSnapshot;
import com.example.news.aggregation.agent.execution.service.EffectLatchService;
import com.example.news.aggregation.agent.execution.service.ExecutionRunService;
import com.example.news.aggregation.agent.execution.service.ReplanGuardService;
import com.example.news.aggregation.agent.execution.service.StepClaimService;
import com.example.news.aggregation.agent.workflow.validation.SchemaValidationProperties;
import com.example.news.aggregation.agent.workflow.validation.ToolInputValidator;
import com.example.news.aggregation.agent.workflow.validation.ToolOutputValidator;
import com.example.news.aggregation.agent.workflow.validation.ToolValidationException;
import com.example.news.aggregation.agent.workflow.selector.ToolFailureCategory;
import com.example.news.aggregation.agent.workflow.selector.ToolSelectionResult;
import com.example.news.aggregation.llm.springai.contract.ExecutionEnums;
import com.example.news.aggregation.llm.springai.contract.ExecutionPlan;
import com.example.news.aggregation.llm.springai.contract.FailurePolicy;
import com.example.news.aggregation.llm.springai.contract.RetryPolicy;
import com.example.news.aggregation.llm.springai.decision.DecisionResult;
import com.example.news.aggregation.llm.springai.decision.DecisionTable;
import com.example.news.aggregation.llm.springai.decision.FailureContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于静态执行计划与运行时决策表的工作流执行器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowOrchestrator {

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
    private final ExecutionPersistenceProperties executionPersistenceProperties;
    private final ReplanControlProperties replanControlProperties;
    private final ReplanGuardService replanGuardService;

    private final ExecutionRunService executionRunService;
    private final StepClaimService stepClaimService;
    private final EffectLatchService effectLatchService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public WorkflowContext executePlan(ExecutionPlan plan, WorkflowContext context) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            log.warn("[workflow] empty plan, skip execution");
            return context;
        }
        if (context != null) {
            context.putAttribute("workflow.plan.id", plan.getPlanId());
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

    public WorkflowContext executeWorkflow(WorkflowDefinition workflow, WorkflowContext context) {
        if (workflow == null || workflow.getSteps() == null || workflow.getSteps().isEmpty()) {
            log.warn("[workflow] empty workflow, skip execution");
            return context;
        }
        if (context == null) {
            context = WorkflowContext.builder().build();
        }

        ensureRunContext(workflow, context);
        String runId = context.getRunId();
        executionRunService.markRunning(runId, null);

        List<WorkflowStep> normalizedSteps = normalizeSteps(workflow.getSteps());
        materializeStepPolicies(normalizedSteps);
        stepClaimService.prepareStepRuns(
                runId,
                normalizedSteps,
                resolveGlobalDefaultMaxRetries(),
                executionPersistenceProperties.getRecovery().getMaxRecoveryAttempts(),
                resolvePlanVersion(workflow, context)
        );

        boolean hasDependencies = normalizedSteps.stream().anyMatch(step -> step.getDependsOn() != null && !step.getDependsOn().isEmpty());
        List<String> completed = new CopyOnWriteArrayList<>();
        List<String> failed = new CopyOnWriteArrayList<>();
        boolean failFast = isFailFast(workflow);

        if (!hasDependencies) {
            for (WorkflowStep step : normalizedSteps) {
                if (isRunTerminal(runId)) {
                    log.info("[workflow] run already terminal, stop flat-step dispatch|runId={} |nextStep={}",
                            runId, step.getStepId());
                    break;
                }
                executeStep(step, context, completed, failed, failFast);
                if (isRunTerminal(runId)) {
                    log.info("[workflow] run turned terminal, stop flat-step dispatch|runId={} |lastStep={}",
                            runId, step.getStepId());
                    break;
                }
                if (failFast && !failed.isEmpty()) {
                    break;
                }
            }
        } else {
            executeWithDependencies(normalizedSteps, workflow, context, completed, failed, failFast);
        }

        finalizeWorkflow(context, completed, failed);
        return context;
    }

    public WorkflowContext executeWorkflow(String workflowId, WorkflowContext context) {
        if (workflowId == null || workflowId.isBlank()) {
            log.warn("[workflow] empty workflowId, skip execution");
            return context;
        }
        WorkflowDefinition workflow = workflowRegistry.getWorkflow(workflowId);
        return executeWorkflow(workflow, context);
    }

    public boolean hasWorkflow(String workflowId) {
        return workflowRegistry.containsWorkflow(workflowId);
    }

    /**
     * Build plan hash from normalized workflow content.
     */
    public String computeWorkflowPlanHash(WorkflowDefinition workflow) {
        return buildWorkflowPlanHash(workflow);
    }

    /**
     * Build plan hash by workflowId lookup result.
     */
    public String computeWorkflowPlanHash(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId must not be blank");
        }
        WorkflowDefinition workflow = workflowRegistry.getWorkflow(workflowId);
        if (workflow == null) {
            throw new IllegalStateException("WORKFLOW_NOT_FOUND:" + workflowId);
        }
        return buildWorkflowPlanHash(workflow);
    }

    private void executeWithDependencies(List<WorkflowStep> steps,
                                         WorkflowDefinition workflow,
                                         WorkflowContext context,
                                         List<String> completed,
                                         List<String> failed,
                                         boolean failFast) {
        String runId = context.getRunId();
        Map<String, WorkflowStep> stepMap = new HashMap<>();
        for (WorkflowStep step : steps) {
            stepMap.put(step.getStepId(), step);
        }
        boolean parallelizable = isParallelizable(workflow);
        int round = 0;
        while (completed.size() + failed.size() < stepMap.size()) {
            if (isRunTerminal(runId)) {
                log.info("[workflow] run already terminal, stop dependency scheduling|runId={} |completed={} |failed={}",
                        runId, completed, failed);
                break;
            }
            round++;
            List<WorkflowStep> ready = findReadySteps(stepMap, completed, failed);
            if (ready.isEmpty()) {
                log.warn("[workflow] no ready step found in this round, stop execution loop|runId={} |completed={} |failed={}",
                        context.getRunId(), completed, failed);
                break;
            }
            log.info("[workflow] scheduling round started|runId={} |round={} |readySteps={}",
                    context.getRunId(), round, ready.stream().map(WorkflowStep::getStepId).toList());
            int before = completed.size() + failed.size();
            if (parallelizable) {
                executeParallel(ready, context, completed, failed, failFast);
            } else {
                executeSequential(ready, context, completed, failed, failFast);
            }
            int after = completed.size() + failed.size();
            if (after == before) {
                log.warn("[workflow] no progress after round, break to avoid infinite loop|runId={} |round={} |readySteps={}",
                        context.getRunId(),
                        round,
                        ready.stream().map(WorkflowStep::getStepId).toList());
                for (WorkflowStep step : ready) {
                    addUnique(failed, step.getStepId());
                }
                break;
            }
            if (isRunTerminal(runId)) {
                log.info("[workflow] run turned terminal after scheduling round, stop dispatch|runId={} |round={}",
                        runId, round);
                break;
            }
            if (failFast && !failed.isEmpty()) {
                break;
            }
        }
    }

    private void executeParallel(List<WorkflowStep> readySteps,
                                 WorkflowContext context,
                                 List<String> completed,
                                 List<String> failed,
                                 boolean failFast) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (WorkflowStep step : readySteps) {
            futures.add(CompletableFuture.runAsync(() -> executeStep(step, context, completed, failed, failFast)));
        }
        for (CompletableFuture<Void> future : futures) {
            try {
                future.join();
            } catch (Exception e) {
                log.warn("[workflow] execute step failed in round loop|runId={} |error={}", context.getRunId(), e.getMessage());
            }
        }
    }

    private void executeSequential(List<WorkflowStep> readySteps,
                                   WorkflowContext context,
                                   List<String> completed,
                                   List<String> failed,
                                   boolean failFast) {
        String runId = context.getRunId();
        for (WorkflowStep step : readySteps) {
            if (isRunTerminal(runId)) {
                log.info("[workflow] run already terminal, skip remaining ready steps|runId={} |stepId={}",
                        runId, step.getStepId());
                break;
            }
            executeStep(step, context, completed, failed, failFast);
            if (isRunTerminal(runId)) {
                log.info("[workflow] run turned terminal, stop sequential dispatch|runId={} |stepId={}",
                        runId, step.getStepId());
                break;
            }
            if (failFast && !failed.isEmpty()) {
                break;
            }
        }
    }

    private void executeStep(WorkflowStep step,
                             WorkflowContext context,
                             List<String> completed,
                             List<String> failed,
                             boolean failFast) {
        String traceId = resolveMetricsTraceId(context);
        RetrievalMetricsContext.bindTrace(traceId);
        LlmMetricsContext.bindTrace(traceId);
        String runId = context.getRunId();
        String stepId = step.getStepId();

        if (isRunTerminal(runId)) {
            log.info("[workflow] skip step because run is terminal|runId={} |stepId={}", runId, stepId);
            return;
        }

        if (!stepClaimService.claimStepCas(runId, stepId)) {
            // Step claim failed: treat as unclaimed to keep flow idempotent and observable.
            handleUnclaimedStep(runId, stepId, context, completed, failed);
            return;
        }

        if (isRunTerminal(runId)) {
            // Step may be claimed while another branch has already terminated the run.
            stepClaimService.markTerminal(
                    runId,
                    stepId,
                    StepStatus.SKIPPED.name(),
                    "run_terminal_skip",
                    "RUN_TERMINAL",
                    "skip claimed step because run already terminal"
            );
            log.info("[workflow] claimed step skipped because run is terminal|runId={} |stepId={}", runId, stepId);
            return;
        }

        ToolSelectionResult selection = stepClaimService.selectToolForExecution(
                runId,
                step,
                false,
                false
        );
        if (selection == null || !selection.hasSelectedTool()) {
            log.warn("[workflow] 步骤无可用工具，转入 fallback_unavailable 分流|runId={} |stepId={} |reasonCode={}",
                    runId, stepId, selection == null ? null : selection.getReasonCode());
            handleFallbackUnavailable(
                    step,
                    context,
                    new IllegalStateException("fallback_unavailable"),
                    failed,
                    selection == null ? null : selection.getReasonCode()
            );
            return;
        }

        WorkflowStep runtimeStep = copyStep(step);
        runtimeStep.setCapabilityName(selection.getSelectedTool());
        if (isRunTerminal(runId)) {
            stepClaimService.markTerminal(
                    runId,
                    stepId,
                    StepStatus.SKIPPED.name(),
                    "run_terminal_skip",
                    "RUN_TERMINAL",
                    "skip selected step because run already terminal"
            );
            log.info("[workflow] selected step skipped because run is terminal|runId={} |stepId={} |tool={}",
                    runId, stepId, selection.getSelectedTool());
            return;
        }
        executionRunService.markRunning(runId, stepId);
        applySelectionToContext(context, stepId, selection);
        context.putAttribute("workflow.runtime.step." + stepId, stepClaimService.buildStepRuntimeView(runId, stepId));
        StepHeartbeat heartbeat = startHeartbeat(runId, stepId);

        String effectKey = null;
        String providerTrace = null;
        long startedAt = System.currentTimeMillis();
        try {
            log.info("[workflow] 步骤开始执行|runId={} |stepId={} |capability={} |sideEffect={}",
                    runId, stepId, runtimeStep.getCapabilityName(), runtimeStep.getSideEffect());

            if (isWriteSideEffect(runtimeStep)) {
                effectKey = effectLatchService.buildEffectKey(runId, stepId);
                ExecutionEffectLatchEntity latch = effectLatchService.reserve(
                        runId,
                        stepId,
                        effectKey,
                        executionRunService.sha256Hex(safeJson(runtimeStep.getParameters()))
                );
                if (latch == null) {
                    throw new IllegalStateException("effect_latch_reserve_failed");
                }
                String effectStatus = latch.getStatus();
                context.putAttribute("workflow.effect.status." + stepId, effectStatus);
                context.putAttribute("workflow.effect.status", effectStatus);
                if (latch.getProviderTrace() != null && !latch.getProviderTrace().isBlank()) {
                    providerTrace = latch.getProviderTrace();
                    context.putAttribute("workflow.effect.providerTrace." + stepId, providerTrace);
                }
                if (EffectStatus.APPLIED.name().equals(effectStatus)) {
                    log.info("[workflow] 副作用幂等闩锁已是 APPLIED，跳过重复执行|runId={} |stepId={} |effectKey={}",
                            runId, stepId, effectKey);
                    stepClaimService.markSucceeded(runId, stepId, latch.getResponseDigest());
                    addUnique(completed, stepId);
                    return;
                }
                if (!EffectStatus.RESERVED.name().equals(effectStatus)) {
                    throw new IllegalStateException("effect_latch_status_invalid:" + effectStatus);
                }
            }
            toolInputValidator.validate(runtimeStep, context);
            Object output = executeCapability(runtimeStep.getCapabilityName(), runtimeStep.getParameters(), context);
            providerTrace = resolveProviderTrace(output, context, stepId);
            toolOutputValidator.validate(runtimeStep, context, output);

            String outputSummary = summarizeAndMaskOutput(output);
            stepClaimService.updateOutputSnapshot(runId, stepId, outputSummary);
            stepClaimService.markSucceeded(runId, stepId, outputSummary);
            stepClaimService.recordToolSuccess(runtimeStep.getCapabilityName(), step.getCapabilityName(),
                    Math.max(0, System.currentTimeMillis() - startedAt));
            context.putAttribute("workflow.execution.attempt", stepClaimService.getAttempt(runId, stepId));
            context.putAttribute("workflow.execution.reason", "");

            if (effectKey != null) {
                effectLatchService.markApplied(runId, stepId, effectKey, providerTrace, outputSummary);
                context.putAttribute("workflow.effect.status." + stepId, EffectStatus.APPLIED.name());
                context.putAttribute("workflow.effect.status", EffectStatus.APPLIED.name());
            }

            addUnique(completed, stepId);
            log.info("[workflow] 步骤执行成功|runId={} |stepId={}", runId, stepId);
        } catch (Exception e) {
            String failureReasonCode = resolveFailureReasonCode(e);
            ToolFailureCategory failureCategory = resolveToolFailureCategory(e, failureReasonCode);
            stepClaimService.recordToolFailure(
                    runtimeStep.getCapabilityName(),
                    step.getCapabilityName(),
                    failureCategory,
                    Math.max(0, System.currentTimeMillis() - startedAt),
                    failureReasonCode
            );
            DecisionResult decision = decideFailure(step, context, e);
            context.putAttribute("workflow.lastDecision." + stepId, decision);
            String reasonCode = decision.getReasonCode();
            context.putAttribute("workflow.execution.attempt", stepClaimService.getAttempt(runId, stepId));
            context.putAttribute("workflow.execution.reason", reasonCode);

            if (effectKey != null) {
                if (providerTrace == null || providerTrace.isBlank()) {
                    Object fromContext = context.getAttributes().get("workflow.effect.providerTrace." + stepId);
                    providerTrace = fromContext == null ? null : String.valueOf(fromContext);
                }
                effectLatchService.markUnknown(
                        runId,
                        stepId,
                        effectKey,
                        providerTrace,
                        "STEP_EXCEPTION",
                        truncateError(e.getMessage())
                );
                context.putAttribute("workflow.effect.status." + stepId, EffectStatus.UNKNOWN.name());
                context.putAttribute("workflow.effect.status", EffectStatus.UNKNOWN.name());
            }

            log.warn("[workflow] 步骤执行失败|runId={} |stepId={} |action={} |reason={} |error={}",
                    runId, stepId, decision.getAction(), reasonCode, e.getMessage());

            if (decision.getAction() == ExecutionEnums.DecisionAction.RETRY) {
                boolean retryScheduled = stepClaimService.markRetryPending(
                        runId,
                        stepId,
                        reasonCode,
                        reasonCode,
                        truncateError(e.getMessage())
                );
                if (retryScheduled) {
                    log.info("[workflow] 已安排步骤重试|runId={} |stepId={}", runId, stepId);
                    executeStep(step, context, completed, failed, failFast);
                    return;
                }
            }

            if (decision.getAction() == ExecutionEnums.DecisionAction.FALLBACK_TOOL) {
                StepClaimService.FallbackScheduleResult fallbackResult = stepClaimService.scheduleFallbackRetryDetailed(
                        runId,
                        stepId,
                        "fallback_tool",
                        "FALLBACK_TOOL",
                        truncateError(e.getMessage())
                );
                if (fallbackResult.hasSelectedTool()) {
                    String fallbackTool = fallbackResult.selectedTool();
                    context.putAttribute("workflow.fallback.required", true);
                    context.putAttribute("workflow.fallback.selected." + stepId, fallbackTool);
                    context.putAttribute("workflow.execution.reason", "fallback_tool");
                    log.info("[workflow] 已选择回退工具并继续执行|runId={} |stepId={} |fromTool={} |toTool={}",
                            runId, stepId, runtimeStep.getCapabilityName(), fallbackTool);
                    executeStep(step, context, completed, failed, failFast);
                    return;
                }
                handleFallbackUnavailable(step, context, e, failed, fallbackResult.failureReasonCode());
                return;
            }
            handleFailureDecision(step, context, decision, e, failed);
            if (failFast) {
                return;
            }
        } finally {
            heartbeat.stop();
            RetrievalMetricsContext.unbindTrace();
            LlmMetricsContext.unbindTrace();
        }
    }

    private String resolveMetricsTraceId(WorkflowContext context) {
        if (context == null || context.getAttributes() == null) {
            return "UNKNOWN";
        }
        Object turnId = context.getAttributes().get("turnId");
        if (turnId == null) {
            return "UNKNOWN";
        }
        String value = String.valueOf(turnId).trim();
        return value.isEmpty() ? "UNKNOWN" : value;
    }

    private void handleUnclaimedStep(String runId,
                                     String stepId,
                                     WorkflowContext context,
                                     List<String> completed,
                                     List<String> failed) {
        ExecutionStepRunEntity current = stepClaimService.findStepRun(runId, stepId);
        if (current == null || current.getStatus() == null) {
            log.warn("[workflow] 执行后未读取到 step 状态，按失败处理|runId={} |stepId={}", runId, stepId);
            addUnique(failed, stepId);
            context.putAttribute("workflow.error", "step_run_not_found");
            return;
        }

        String status = current.getStatus();
        if (StepStatus.SUCCEEDED.name().equals(status) || StepStatus.SKIPPED.name().equals(status)) {
            addUnique(completed, stepId);
            log.info("[workflow] 观察到步骤终态|runId={} |stepId={} |status={}", runId, stepId, status);
            return;
        }
        if (StepStatus.FAILED.name().equals(status)) {
            addUnique(failed, stepId);
            context.putAttribute("workflow.error", truncateError(current.getErrorMessage()));
            log.warn("[workflow] 步骤终态为 FAILED|runId={} |stepId={} |reason={}",
                    runId, stepId, current.getReasonCode());
            return;
        }
        if (StepStatus.WAITING.name().equals(status)) {
            context.putAttribute("workflow.waiting", true);
            context.putAttribute("workflow.waiting.reason",
                    current.getReasonCode() == null ? "need_user_input" : current.getReasonCode());
            context.putAttribute("workflow.waiting.stepId", stepId);
            addUnique(failed, stepId);
            log.info("[workflow] 步骤终态为 WAITING|runId={} |stepId={} |reason={}",
                    runId, stepId, current.getReasonCode());
            return;
        }

        // RUNNING/PENDING states are transient and should be rare on this path.
        log.info("[workflow] 步骤状态仍为瞬时态（RUNNING/PENDING）|runId={} |stepId={} |status={}",
                runId, stepId, status);
    }
    private void handleFailureDecision(WorkflowStep step,
                                       WorkflowContext context,
                                       DecisionResult decision,
                                       Exception e,
                                       List<String> failed) {
        String runId = context.getRunId();
        String stepId = step.getStepId();
        String reasonCode = decision.getReasonCode();

        switch (decision.getAction()) {
            case WAIT -> {
                context.putAttribute("workflow.waiting", true);
                context.putAttribute("workflow.waiting.reason", reasonCode);
                context.putAttribute("workflow.waiting.stepId", stepId);
                stepClaimService.markWaiting(runId, stepId, reasonCode, "NEED_USER_INPUT", truncateError(e.getMessage()));
                executionRunService.markWaiting(runId, stepId, reasonCode, truncateError(e.getMessage()));
            }
            case REPLAN -> {
                context.putAttribute("workflow.replan.required", true);
                String finalReasonCode = persistReplanAttempt(runId, stepId, reasonCode, context);
                stepClaimService.markFailed(runId, stepId, finalReasonCode, "REPLAN_REQUIRED", truncateError(e.getMessage()));
            }
            case FALLBACK_TOOL -> {
                handleFallbackUnavailable(step, context, e, failed, null);
                return;
            }
            case DEGRADE_OUTPUT -> {
                context.putAttribute("workflow.degrade.required", true);
                context.putAttribute("workflow.degrade.reason", reasonCode);
                context.putAttribute("workflow.degrade.stepId", stepId);
                context.putAttribute("workflow.quality.gate", true);
                if (!context.getAttributes().containsKey("answer")
                        || context.getAttributes().get("answer") == null
                        || String.valueOf(context.getAttributes().get("answer")).isBlank()) {
                    context.putAttribute("answer", "Result is incomplete and has been degraded.");
                }
                // Degraded success: mark step as succeeded with minimal output payload.
                stepClaimService.markSucceeded(runId, stepId, "{\"degraded\":true}");
                return;
            }
            case ABORT -> {
                stepClaimService.markFailed(runId, stepId, reasonCode, "ABORTED", truncateError(e.getMessage()));
                executionRunService.markAborted(runId, reasonCode, truncateError(e.getMessage()));
            }
            default -> stepClaimService.markFailed(runId, stepId, reasonCode, "STEP_FAILED", truncateError(e.getMessage()));
        }

        addUnique(failed, stepId);
        context.putAttribute("workflow.error", truncateError(e.getMessage()));
    }

    private void applySelectionToContext(WorkflowContext context, String stepId, ToolSelectionResult selection) {
        if (context == null || stepId == null || selection == null) {
            return;
        }
        // 选择结果回填到上下文，便于链路日志、诊断与回放比对。
        context.putAttribute("workflow.tool.selected." + stepId, selection.getSelectedTool());
        context.putAttribute("workflow.tool.selection.reason." + stepId, selection.getReasonCode());
        if (selection.getCircuitStateByTool() != null) {
            context.putAttribute("workflow.tool.circuit.state." + stepId, selection.getCircuitStateByTool());
            for (Map.Entry<String, String> entry : selection.getCircuitStateByTool().entrySet()) {
                context.putAttribute("workflow.tool.circuit.state." + entry.getKey(), entry.getValue());
            }
        }
        if (selection.getHealthSnapshotByTool() != null) {
            context.putAttribute("workflow.tool.health.snapshot." + stepId, selection.getHealthSnapshotByTool());
            for (Map.Entry<String, ?> entry : selection.getHealthSnapshotByTool().entrySet()) {
                context.putAttribute("workflow.tool.health.snapshot." + entry.getKey(), entry.getValue());
            }
        }
    }

    private void handleFallbackUnavailable(WorkflowStep step,
                                           WorkflowContext context,
                                           Exception e,
                                           List<String> failed,
                                           String unavailableReasonCode) {
        String runId = context.getRunId();
        String stepId = step.getStepId();
        DecisionResult decision = decideFallbackUnavailable(step);
        context.putAttribute("workflow.lastDecision." + stepId, decision);
        String message = truncateError(e == null ? null : e.getMessage());
        String fallbackReasonCode = normalizeFallbackUnavailableReason(unavailableReasonCode);
        log.warn("[workflow] fallback 不可调度，进入决策分流|runId={} |stepId={} |reasonCode={} |action={}",
                runId, stepId, fallbackReasonCode, decision == null ? null : decision.getAction());
        if (decision != null && decision.getAction() == ExecutionEnums.DecisionAction.WAIT) {
            context.putAttribute("workflow.waiting", true);
            context.putAttribute("workflow.waiting.reason", fallbackReasonCode);
            context.putAttribute("workflow.waiting.stepId", stepId);
            stepClaimService.markWaiting(runId, stepId, fallbackReasonCode, "FALLBACK_UNAVAILABLE", message);
            executionRunService.markWaiting(runId, stepId, fallbackReasonCode, message);
            addUnique(failed, stepId);
            log.info("[workflow] fallback 不可用后转 WAITING|runId={} |stepId={} |reasonCode={}",
                    runId, stepId, fallbackReasonCode);
            return;
        }
        if (decision != null && decision.getAction() == ExecutionEnums.DecisionAction.REPLAN) {
            context.putAttribute("workflow.replan.required", true);
            String finalReasonCode = persistReplanAttempt(runId, stepId, fallbackReasonCode, context);
            stepClaimService.markFailed(runId, stepId, finalReasonCode, "REPLAN_REQUIRED", message);
            executionRunService.markFailed(runId, "REPLAN_REQUIRED", message);
            addUnique(failed, stepId);
            context.putAttribute("workflow.error", message);
            log.info("[workflow] fallback 不可用后转 REPLAN|runId={} |stepId={} |reasonCode={}",
                    runId, stepId, finalReasonCode);
            return;
        }
        stepClaimService.markFailed(runId, stepId, fallbackReasonCode, "FALLBACK_UNAVAILABLE", message);
        executionRunService.markAborted(runId, "FALLBACK_UNAVAILABLE", message);
        addUnique(failed, stepId);
        context.putAttribute("workflow.error", message);
        log.warn("[workflow] fallback 不可用后执行 ABORT|runId={} |stepId={} |reasonCode={}",
                runId, stepId, fallbackReasonCode);
    }

    private DecisionResult decideFallbackUnavailable(WorkflowStep step) {
        FailureContext failureContext = FailureContext.builder()
                .errorCategory(ExecutionEnums.ErrorCategory.RETRYABLE_TOOL_ERROR)
                .failureReasonCode("fallback_unavailable")
                .retryCount(resolveMaxRetries(step))
                .maxRetries(resolveMaxRetries(step))
                .hasFallbackTool(false)
                .replanAllowed(resolveReplanAllowed(step))
                .replanFeatureEnabled(replanControlProperties.isEnabled())
                .needsExternalSignal(resolveNeedsExternalSignal(step, "fallback_unavailable"))
                .sideEffect(resolveSideEffect(step))
                .effectState(resolveEffectState(step))
                .preferredResumeMode(resolvePreferredResumeMode(step))
                .build();
        return decisionTable.resolve(failureContext);
    }

    private WorkflowStep copyStep(WorkflowStep sourceStep) {
        if (sourceStep == null) {
            return null;
        }
        RetryPolicy retryPolicy = sourceStep.getRetryPolicy() == null ? null : RetryPolicy.builder()
                .maxRetries(sourceStep.getRetryPolicy().getMaxRetries())
                .backoffMs(sourceStep.getRetryPolicy().getBackoffMs())
                .retryableErrorCodes(sourceStep.getRetryPolicy().getRetryableErrorCodes() == null
                        ? null
                        : new ArrayList<>(sourceStep.getRetryPolicy().getRetryableErrorCodes()))
                .build();
        FailurePolicy failurePolicy = sourceStep.getFailurePolicy() == null ? null : FailurePolicy.builder()
                .fallbackTools(sourceStep.getFailurePolicy().getFallbackTools() == null
                        ? null
                        : new ArrayList<>(sourceStep.getFailurePolicy().getFallbackTools()))
                .replanAllowed(sourceStep.getFailurePolicy().isReplanAllowed())
                .needUserInputOnFailure(sourceStep.getFailurePolicy().isNeedUserInputOnFailure())
                .resumeMode(sourceStep.getFailurePolicy().getResumeMode())
                .build();
        return WorkflowStep.builder()
                .stepId(sourceStep.getStepId())
                .capabilityName(sourceStep.getCapabilityName())
                .dependsOn(sourceStep.getDependsOn() == null ? null : new ArrayList<>(sourceStep.getDependsOn()))
                .parameters(sourceStep.getParameters() == null ? null : new LinkedHashMap<>(sourceStep.getParameters()))
                .sideEffect(sourceStep.getSideEffect())
                .doneCheckRef(sourceStep.getDoneCheckRef())
                .outputSchema(sourceStep.getOutputSchema() == null ? null : new LinkedHashMap<>(sourceStep.getOutputSchema()))
                .doneCheck(sourceStep.getDoneCheck())
                .schemaVersion(sourceStep.getSchemaVersion())
                .semanticVersion(sourceStep.getSemanticVersion())
                .retryPolicy(retryPolicy)
                .failurePolicy(failurePolicy)
                .build();
    }
    private DecisionResult decideFailure(WorkflowStep step, WorkflowContext context, Exception e) {
        String reasonCode = resolveFailureReasonCode(e);
        String runId = context != null ? context.getRunId() : null;
        int retryCount = runId == null ? 0 : stepClaimService.getAttempt(runId, step.getStepId());
        int maxRetries = runId == null
                ? resolveMaxRetries(step)
                : stepClaimService.getMaxRetries(runId, step.getStepId(), resolveMaxRetries(step));
        ExecutionRunEntity runEntity = runId == null ? null : executionRunService.findByRunId(runId);
        ExecutionStepRunEntity stepRunEntity = runId == null ? null : stepClaimService.findStepRun(runId, step.getStepId());
        ReplanGuardService.BudgetCheckResult budgetResult = replanGuardService.checkBudget(runEntity, stepRunEntity);
        ReplanEvidenceSnapshot evidenceSnapshot = buildReplanEvidenceSnapshot(context);
        ReplanGuardService.EvidenceCheckResult evidenceResult = replanGuardService.checkEvidence(step.getCapabilityName(), evidenceSnapshot);
        boolean nonRetryableReason = replanGuardService.isNonRetryableReason(reasonCode);
        ReplanChangeProof changeProof = buildReplanChangeProof(step, stepRunEntity, reasonCode, context);
        boolean noEffectiveChange = changeProof != null && !changeProof.isEffectiveChange();
        if (context != null) {
            context.putAttribute("workflow.replan.changeProof." + step.getStepId(), changeProof);
            context.putAttribute("workflow.replan.evidence." + step.getStepId(), evidenceSnapshot);
        }

        FailureContext failureContext = FailureContext.builder()
                .errorCategory(resolveErrorCategory(e, reasonCode))
                .failureReasonCode(reasonCode)
                .retryCount(retryCount)
                .maxRetries(maxRetries)
                .hasFallbackTool(resolveHasFallback(step))
                .replanAllowed(resolveReplanAllowed(step))
                .replanFeatureEnabled(replanControlProperties.isEnabled())
                .needsExternalSignal(resolveNeedsExternalSignal(step, reasonCode))
                .sideEffect(resolveSideEffect(step))
                .effectState(resolveEffectState(step))
                .preferredResumeMode(resolvePreferredResumeMode(step))
                .replanCountRun(runEntity == null ? 0 : runEntity.getReplanCountRun())
                .maxReplansPerRun(replanControlProperties.getMaxReplansPerRun())
                .replanCountStep(stepRunEntity == null ? 0 : stepRunEntity.getReplanCountStep())
                .maxReplansPerStep(replanControlProperties.getMaxReplansPerStep())
                .evidenceSufficient(evidenceResult.sufficient())
                .replanNoEffectiveChange(noEffectiveChange)
                .replanNonRetryableReason(nonRetryableReason)
                .build();

        DecisionResult decision = decisionTable.resolve(failureContext);
        log.info("[workflow] failure decision resolved|runId={} |stepId={} |category={} |retry={}/{} |replanBudgetAllowed={} |evidenceSufficient={} |changeEffective={} |action={} |nextState={} |reason={}",
                runId,
                step.getStepId(),
                failureContext.getErrorCategory(),
                failureContext.getRetryCount(),
                failureContext.getMaxRetries(),
                budgetResult.allowed(),
                evidenceResult.sufficient(),
                changeProof == null ? null : changeProof.isEffectiveChange(),
                decision.getAction(),
                decision.getNextState(),
                decision.getReasonCode());
        return decision;
    }

    /**
     * Replan 审计回填与预算扣减。
     * 设计约束：
     * 1. 先扣 run 级预算，再写 step 级 Replan 快照，失败时透出专用 reasonCode；
     * 2. 当前版本仅做“审计与预算”落库，不在本方法内执行新计划切换。
     */
    private String persistReplanAttempt(String runId, String stepId, String reasonCode, WorkflowContext context) {
        String normalizedReason = reasonCode == null || reasonCode.isBlank() ? "replan_required" : reasonCode;
        ExecutionRunEntity runEntity = executionRunService.findByRunId(runId);
        int activePlanVersion = runEntity == null || runEntity.getActivePlanVersion() == null
                ? 1
                : Math.max(1, runEntity.getActivePlanVersion());
        boolean runBudgetUpdated = replanGuardService.increaseBudgetAfterPlanActivated(runId, activePlanVersion);
        if (!runBudgetUpdated) {
            log.warn("[workflow] Replan 回填失败：run 预算 CAS 更新失败|runId={} |stepId={} |activePlanVersion={}",
                    runId, stepId, activePlanVersion);
            return "replan_cas_exhausted";
        }
        boolean stepRecorded = stepClaimService.recordReplanAttempt(
                runId,
                stepId,
                normalizedReason,
                resolveReplanSnapshotJson(context, stepId, "workflow.replan.changeProof."),
                resolveReplanSnapshotJson(context, stepId, "workflow.replan.evidence."),
                "REPLAN"
        );
        if (!stepRecorded) {
            log.warn("[workflow] Replan 回填失败：step 快照 CAS 更新失败|runId={} |stepId={} |reasonCode={}",
                    runId, stepId, normalizedReason);
            return "replan_cas_exhausted";
        }
        return normalizedReason;
    }

    private String resolveReplanSnapshotJson(WorkflowContext context, String stepId, String keyPrefix) {
        if (context == null || context.getAttributes() == null || stepId == null || keyPrefix == null) {
            return null;
        }
        Object value = context.getAttributes().get(keyPrefix + stepId);
        if (value == null) {
            return null;
        }
        return safeJson(value);
    }

    private ReplanChangeProof buildReplanChangeProof(WorkflowStep step,
                                                     ExecutionStepRunEntity stepRunEntity,
                                                     String reasonCode,
                                                     WorkflowContext context) {
        if (step == null) {
            return null;
        }
        Map<String, Object> previousInput = copyMap(step.getParameters());
        Map<String, Object> candidateInput = buildCandidateReplanInput(step, stepRunEntity, reasonCode, context);
        String constraintsDigest = buildConstraintsDigest(step);
        String depsDigest = buildDepsDigest(step);
        return replanGuardService.buildChangeProof(
                step.getCapabilityName(),
                previousInput,
                candidateInput,
                constraintsDigest,
                depsDigest
        );
    }

    private String buildConstraintsDigest(WorkflowStep step) {
        if (step == null) {
            return "";
        }
        String schema = step.getSchemaVersion() == null ? "" : step.getSchemaVersion();
        String semantic = step.getSemanticVersion() == null ? "" : step.getSemanticVersion();
        String doneCheckRef = step.getDoneCheckRef() == null ? "" : step.getDoneCheckRef();
        String outputSchemaDigest = step.getOutputSchema() == null ? "" : safeJson(step.getOutputSchema());
        return schema + "|" + semantic + "|" + doneCheckRef + "|" + outputSchemaDigest;
    }

    private String buildDepsDigest(WorkflowStep step) {
        if (step == null || step.getDependsOn() == null || step.getDependsOn().isEmpty()) {
            return "";
        }
        List<String> deps = new ArrayList<>(step.getDependsOn());
        Collections.sort(deps);
        return String.join(",", deps);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildCandidateReplanInput(WorkflowStep step,
                                                          ExecutionStepRunEntity stepRunEntity,
                                                          String reasonCode,
                                                          WorkflowContext context) {
        Map<String, Object> candidate = copyMap(step == null ? null : step.getParameters());
        String stepId = step == null ? null : step.getStepId();
        if (context != null && context.getAttributes() != null && stepId != null) {
            Object patch = context.getAttributes().get("workflow.replan.patch." + stepId);
            if (patch instanceof Map<?, ?> patchMap) {
                for (Map.Entry<?, ?> entry : patchMap.entrySet()) {
                    if (entry.getKey() == null) {
                        continue;
                    }
                    candidate.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return candidate;
            }
        }
        int nextStepReplanCount = stepRunEntity == null || stepRunEntity.getReplanCountStep() == null
                ? 1
                : Math.max(1, stepRunEntity.getReplanCountStep() + 1);
        candidate.put("_replanAttemptHint", nextStepReplanCount);
        candidate.put("_replanReasonHint", reasonCode == null ? "replan_required" : reasonCode);
        return candidate;
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(source);
    }

    private void finalizeWorkflow(WorkflowContext context, List<String> completed, List<String> failed) {
        if (context == null) {
            return;
        }
        context.putAttribute("workflow.completedSteps", completed);
        context.putAttribute("workflow.failedSteps", failed);

        String runId = context.getRunId();
        ExecutionRunEntity currentRun = runId == null || runId.isBlank() ? null : executionRunService.findByRunId(runId);
        String persistedStatus = currentRun == null ? null : currentRun.getStatus();
        if (isTerminalRunStatus(persistedStatus)) {
            context.putAttribute("workflow.status", mapRunStatusToWorkflowStatus(persistedStatus));
            log.info("[workflow] workflow finished by persisted terminal status|runId={} |status={} |completed={} |failed={}",
                    runId, persistedStatus, completed, failed);
            return;
        }

        if (Boolean.TRUE.equals(context.getAttributes().get("workflow.waiting"))) {
            context.putAttribute("workflow.status", "WAITING");
            executionRunService.markWaiting(
                    runId,
                    String.valueOf(context.getAttributes().getOrDefault("workflow.waiting.stepId", "")),
                    String.valueOf(context.getAttributes().getOrDefault("workflow.waiting.reason", "need_user_input")),
                    null
            );
            log.info("[workflow] workflow finished|runId={} |status=WAITING |completed={} |failed={}",
                    runId, completed, failed);
            return;
        }

        if (failed.isEmpty()) {
            context.putAttribute("workflow.status", "SUCCESS");
            executionRunService.markSucceeded(runId);
            log.info("[workflow] workflow finished|runId={} |status=SUCCESS |completed={} |failed={}",
                    runId, completed, failed);
            return;
        }

        context.putAttribute("workflow.status", "FAILED");
        executionRunService.markFailed(runId, "WORKFLOW_FAILED", "step failed");
        log.info("[workflow] workflow finished|runId={} |status=FAILED |completed={} |failed={}",
                runId, completed, failed);
    }
    private void ensureRunContext(WorkflowDefinition workflow, WorkflowContext context) {
        if (context.getRunId() != null && !context.getRunId().isBlank()) {
            return;
        }
        String sessionId = context.getSessionId() == null ? "unknown-session" : context.getSessionId();
        String turnId = String.valueOf(context.getAttributes().getOrDefault("turnId", "adhoc-turn"));
        String requestHash = executionRunService.sha256Hex(sessionId + "|" + turnId + "|" + context.getQuery());
        String dedupeKey = executionRunService.buildRequestDedupeKey(sessionId, turnId, requestHash);
        String planHash = buildWorkflowPlanHash(workflow);

        var run = executionRunService.createOrReplayRun(
                sessionId,
                turnId,
                dedupeKey,
                planHash,
                workflow.getId()
        );
        context.setRunId(run.getRunId());
        context.setWorkerId(executionPersistenceProperties.getPersistence().getWorkerId());
        context.putAttribute("workflow.request.dedupe.key", dedupeKey);
        context.putAttribute("workflow.plan.hash", planHash);
        log.info("[workflow] run persisted before execution|runId={} |sessionId={} |turnId={} |workflowId={}",
                run.getRunId(), sessionId, turnId, workflow.getId());
    }

    /**
     * Build deterministic plan hash from normalized workflow structure.
     */
    private String buildWorkflowPlanHash(WorkflowDefinition workflow) {
        if (workflow == null) {
            return executionRunService.sha256Hex("");
        }
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("id", workflow.getId());
        canonical.put("name", workflow.getName());
        canonical.put("metadata", canonicalizeValue(workflow.getMetadata()));

        List<Map<String, Object>> steps = new ArrayList<>();
        if (workflow.getSteps() != null) {
            for (WorkflowStep step : workflow.getSteps()) {
                if (step == null) {
                    continue;
                }
                Map<String, Object> stepCanonical = new LinkedHashMap<>();
                stepCanonical.put("stepId", step.getStepId());
                stepCanonical.put("capabilityName", step.getCapabilityName());
                stepCanonical.put("dependsOn", canonicalizeValue(step.getDependsOn()));
                stepCanonical.put("parameters", canonicalizeValue(step.getParameters()));
                stepCanonical.put("sideEffect", step.getSideEffect());
                stepCanonical.put("doneCheckRef", step.getDoneCheckRef());
                stepCanonical.put("outputSchema", canonicalizeValue(step.getOutputSchema()));
                stepCanonical.put("doneCheck", canonicalizeValue(step.getDoneCheck()));
                stepCanonical.put("schemaVersion", step.getSchemaVersion());
                stepCanonical.put("semanticVersion", step.getSemanticVersion());
                stepCanonical.put("retryPolicy", canonicalizeValue(step.getRetryPolicy()));
                stepCanonical.put("failurePolicy", canonicalizeValue(step.getFailurePolicy()));
                steps.add(stepCanonical);
            }
        }
        canonical.put("steps", steps);
        return executionRunService.sha256Hex(safeJson(canonical));
    }

    @SuppressWarnings("unchecked")
    private Object canonicalizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                sorted.put(String.valueOf(entry.getKey()), canonicalizeValue(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object item : list) {
                normalized.add(canonicalizeValue(item));
            }
            return normalized;
        }
        if (value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Enum<?>) {
            return value;
        }
        try {
            Map<String, Object> mapped = objectMapper.convertValue(value, Map.class);
            return canonicalizeValue(mapped);
        } catch (IllegalArgumentException ignore) {
            return String.valueOf(value);
        }
    }

    private void materializeStepPolicies(List<WorkflowStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        for (WorkflowStep step : steps) {
            if (step == null) {
                continue;
            }
            FailurePolicy original = step.getFailurePolicy();
            FailurePolicy merged = FailurePolicy.builder()
                    .fallbackTools(resolveEffectiveFallbackTools(step))
                    .replanAllowed(resolveReplanAllowed(step))
                    .needUserInputOnFailure(original != null && original.isNeedUserInputOnFailure())
                    .resumeMode(resolvePreferredResumeMode(step))
                    .build();
            step.setFailurePolicy(merged);
        }
    }

    private List<WorkflowStep> normalizeSteps(List<WorkflowStep> steps) {
        List<WorkflowStep> normalized = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            WorkflowStep source = steps.get(i);
            if (source == null) {
                continue;
            }
            WorkflowStep step = copyStep(source);
            if (step.getStepId() == null || step.getStepId().isBlank()) {
                step.setStepId("step-" + i);
            }
            normalized.add(step);
        }
        return normalized;
    }

    private List<WorkflowStep> findReadySteps(Map<String, WorkflowStep> stepMap,
                                              List<String> completed,
                                              List<String> failed) {
        List<WorkflowStep> ready = new ArrayList<>();
        for (WorkflowStep step : stepMap.values()) {
            String stepId = step.getStepId();
            if (completed.contains(stepId) || failed.contains(stepId)) {
                continue;
            }
            List<String> deps = step.getDependsOn();
            if (deps == null || deps.isEmpty() || deps.stream().allMatch(completed::contains)) {
                ready.add(step);
            }
        }
        return ready;
    }

    private String resolveProviderTrace(Object output, WorkflowContext context, String stepId) {
        if (output instanceof Map<?, ?> outputMap) {
            String providerTrace = firstNonBlank(
                    readMapValue(outputMap, "providerTrace"),
                    readMapValue(outputMap, "provider_trace"),
                    readMapValue(outputMap, "traceId"),
                    readMapValue(outputMap, "trace_id"),
                    readMapValue(outputMap, "requestId"),
                    readMapValue(outputMap, "request_id")
            );
            if (providerTrace != null && context != null) {
                context.putAttribute("workflow.effect.providerTrace." + stepId, providerTrace);
            }
            return providerTrace;
        }
        return null;
    }

    private String readMapValue(Map<?, ?> source, String key) {
        if (source == null || key == null || !source.containsKey(key)) {
            return null;
        }
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private StepHeartbeat startHeartbeat(String runId, String stepId) {
        long heartbeatSeconds = Math.max(1L, executionPersistenceProperties.getPersistence().getHeartbeatSeconds());
        long intervalMs = heartbeatSeconds * 1000;
        AtomicBoolean running = new AtomicBoolean(true);

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!running.get()) {
                    return;
                }
                boolean success = stepClaimService.heartbeat(runId, stepId);
                if (!success) {
                    log.warn("[workflow] step heartbeat renewal failed|runId={} |stepId={}, recovery thread will take over", runId, stepId);
                    running.set(false);
                    return;
                }
                log.debug("[workflow] step heartbeat renewed|runId={} |stepId={}", runId, stepId);
            }
        });

        return () -> {
            running.set(false);
            future.cancel(true);
        };
    }

    private void addUnique(List<String> target, String stepId) {
        if (target == null || stepId == null || stepId.isBlank()) {
            return;
        }
        if (!target.contains(stepId)) {
            target.add(stepId);
        }
    }

    @FunctionalInterface
    private interface StepHeartbeat {
        void stop();
    }

    private boolean isWriteSideEffect(WorkflowStep step) {
        ExecutionEnums.SideEffectType type = resolveSideEffect(step);
        return type == ExecutionEnums.SideEffectType.WRITE || type == ExecutionEnums.SideEffectType.EXTERNAL;
    }

    private String summarizeAndMaskOutput(Object output) {
        try {
            String json = safeJson(output);
            // Store masked/truncated output snapshot to avoid oversized payload persistence.
            return json
                    .replaceAll("(?i)\\\"(api[-_]?key|token|secret|password)\\\"\\s*:\\s*\\\"[^\\\"]*\\\"", "\"$1\":\"***\"")
                    .replaceAll("(?i)ALIYUN[A-Z0-9_]*", "***");
        } catch (Exception e) {
            return "{}";
        }
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private int resolveGlobalDefaultMaxRetries() {
        Integer global = schemaValidationProperties.getGlobalMaxRetries();
        return global == null ? 2 : Math.max(0, global);
    }

    /**
     * 解析当前执行使用的计划版本。
     * 优先级：context 显式设置 > workflow metadata > 默认 1。
     */
    private int resolvePlanVersion(WorkflowDefinition workflow, WorkflowContext context) {
        int fromContext = parsePositiveInt(context == null ? null : context.getAttributes().get("workflow.active.plan.version"));
        if (fromContext > 0) {
            return fromContext;
        }
        int fromMetadata = parsePositiveInt(workflow == null || workflow.getMetadata() == null
                ? null
                : workflow.getMetadata().get("planVersion"));
        if (fromMetadata > 0) {
            if (context != null) {
                context.putAttribute("workflow.active.plan.version", fromMetadata);
            }
            return fromMetadata;
        }
        if (context != null) {
            context.putAttribute("workflow.active.plan.version", 1);
        }
        return 1;
    }

    private int parsePositiveInt(Object value) {
        if (value == null) {
            return -1;
        }
        if (value instanceof Number number) {
            int v = number.intValue();
            return v > 0 ? v : -1;
        }
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            return parsed > 0 ? parsed : -1;
        } catch (Exception ignore) {
            return -1;
        }
    }

    /**
     * EvidenceGate v1 只读取已有流水线统计字段，不引入新算法。
     */
    private ReplanEvidenceSnapshot buildReplanEvidenceSnapshot(WorkflowContext context) {
        if (context == null || context.getAttributes() == null) {
            return ReplanEvidenceSnapshot.builder()
                    .sourceCount(0)
                    .coverageRate(0.0d)
                    .clusterCount(0)
                    .build();
        }
        Map<String, Object> attrs = context.getAttributes();
        int sourceCount = parsePositiveIntOrZero(
                attrs.get("workflow.evidence.normalized.count"),
                attrs.get("workflow.evidence.raw.count")
        );
        double coverageRate = parseDoubleOrZero(
                attrs.get("workflow.evidence.coverage.rate"),
                attrs.get("workflow.evidence.coverageRate")
        );
        int clusterCount = parsePositiveIntOrZero(
                attrs.get("workflow.evidence.cluster.count"),
                attrs.get("workflow.evidence.clusterCount")
        );
        return ReplanEvidenceSnapshot.builder()
                .sourceCount(sourceCount)
                .coverageRate(coverageRate)
                .clusterCount(clusterCount)
                .build();
    }

    private int parsePositiveIntOrZero(Object... values) {
        if (values == null) {
            return 0;
        }
        for (Object value : values) {
            int parsed = parsePositiveInt(value);
            if (parsed > 0) {
                return parsed;
            }
        }
        return 0;
    }

    private double parseDoubleOrZero(Object... values) {
        if (values == null) {
            return 0.0d;
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (value instanceof Number number) {
                return Math.max(0.0d, number.doubleValue());
            }
            try {
                return Math.max(0.0d, Double.parseDouble(String.valueOf(value)));
            } catch (Exception ignore) {
                // try next
            }
        }
        return 0.0d;
    }

    private ToolFailureCategory resolveToolFailureCategory(Exception e, String reasonCode) {
        if ("done_check_fail".equals(reasonCode)) {
            return ToolFailureCategory.QUALITY_FAIL;
        }
        if ("output_schema_version_mismatch".equals(reasonCode)
                || "output_missing_required_field_stable".equals(reasonCode)
                || "output_type_mismatch_stable".equals(reasonCode)
                || "schema_version_unsupported_compat_restricted".equals(reasonCode)
                || "output_parse_error".equals(reasonCode)
                || "output_malformed_temporary".equals(reasonCode)) {
            return ToolFailureCategory.SCHEMA_FAIL;
        }
        if (e instanceof ToolValidationException validationException) {
            ExecutionEnums.ErrorCategory category = validationException.getErrorCategory();
            if (category == ExecutionEnums.ErrorCategory.QUALITY_FAIL) {
                return ToolFailureCategory.QUALITY_FAIL;
            }
            if (category == ExecutionEnums.ErrorCategory.NEED_USER_INPUT) {
                return ToolFailureCategory.OTHER;
            }
        }
        String message = e == null || e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (message.contains("timeout") || message.contains("timed out")) {
            return ToolFailureCategory.TIMEOUT;
        }
        if (message.contains("connection")
                || message.contains("network")
                || message.contains("socket")
                || message.contains("refused")
                || message.contains("503")
                || message.contains("502")
                || message.contains("504")) {
            return ToolFailureCategory.INFRA_FAIL;
        }
        return ToolFailureCategory.OTHER;
    }

    private ExecutionEnums.ErrorCategory resolveErrorCategory(Exception e, String reasonCode) {
        if (e instanceof ToolValidationException validationException && validationException.getErrorCategory() != null) {
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

    private List<String> resolveEffectiveFallbackTools(WorkflowStep step) {
        if (step == null) {
            return List.of();
        }
        if (step.getFailurePolicy() != null
                && step.getFailurePolicy().getFallbackTools() != null
                && !step.getFailurePolicy().getFallbackTools().isEmpty()) {
            return step.getFailurePolicy().getFallbackTools();
        }
        if (step.getCapabilityName() != null && schemaValidationProperties.getToolDefaultFallbacks() != null) {
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
        if (!replanControlProperties.isEnabled()) {
            return false;
        }
        if (step == null || step.getFailurePolicy() == null) {
            return false;
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

    private boolean isRunTerminal(String runId) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        ExecutionRunEntity run = executionRunService.findByRunId(runId);
        if (run == null) {
            return false;
        }
        return isTerminalRunStatus(run.getStatus());
    }

    private boolean isTerminalRunStatus(String status) {
        return RunStatus.ABORTED.name().equals(status)
                || RunStatus.FAILED.name().equals(status)
                || RunStatus.SUCCEEDED.name().equals(status)
                || RunStatus.WAITING.name().equals(status);
    }

    private String mapRunStatusToWorkflowStatus(String runStatus) {
        if (RunStatus.SUCCEEDED.name().equals(runStatus)) {
            return "SUCCESS";
        }
        if (RunStatus.WAITING.name().equals(runStatus)) {
            return "WAITING";
        }
        if (RunStatus.ABORTED.name().equals(runStatus)) {
            return "ABORTED";
        }
        if (RunStatus.FAILED.name().equals(runStatus)) {
            return "FAILED";
        }
        return "FAILED";
    }

    private Object executeCapability(String capabilityName, Map<String, Object> parameters, WorkflowContext context) {
        if (capabilityName == null || capabilityName.isBlank()) {
            log.warn("[workflow] empty capabilityName, skip execution");
            return null;
        }
        CapabilityExecutor executor = workflowRegistry.getExecutor(capabilityName);
        if (executor == null) {
            log.warn("[workflow] capability executor not found, skip step|capability={}", capabilityName);
            return null;
        }
        return executor.execute(parameters, context);
    }

    private String truncateError(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        if (errorMessage.length() <= 500) {
            return errorMessage;
        }
        return errorMessage.substring(0, 500);
    }

    private String normalizeFallbackUnavailableReason(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return "fallback_unavailable";
        }
        if ("fallback_cas_exhausted".equals(reasonCode)) {
            return reasonCode;
        }
        return "fallback_unavailable";
    }
}



