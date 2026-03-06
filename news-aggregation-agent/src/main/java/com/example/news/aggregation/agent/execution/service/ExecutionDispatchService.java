package com.example.news.aggregation.agent.execution.service;

import com.example.news.aggregation.agent.execution.config.ExecutionPersistenceProperties;
import com.example.news.aggregation.agent.execution.domain.ExecutionRunEntity;
import com.example.news.aggregation.agent.execution.domain.ExecutionStepRunEntity;
import com.example.news.aggregation.agent.workflow.ExecutionPlanWorkflowAdapter;
import com.example.news.aggregation.agent.workflow.WorkflowContext;
import com.example.news.aggregation.agent.workflow.WorkflowDefinition;
import com.example.news.aggregation.agent.workflow.WorkflowOrchestrator;
import com.example.news.aggregation.agent.workflow.WorkflowStep;
import com.example.news.aggregation.llm.springai.contract.ExecutionEnums;
import com.example.news.aggregation.llm.springai.contract.ExecutionPlan;
import com.example.news.aggregation.llm.springai.contract.FailurePolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从持久化 run/step 状态重建工作流，并继续调度执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionDispatchService {

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

    private final ExecutionRunService executionRunService;
    private final StepClaimService stepClaimService;
    private final WorkflowOrchestrator workflowOrchestrator;
    private final ExecutionPersistenceProperties executionPersistenceProperties;
    private final ExecutionPlanWorkflowAdapter executionPlanWorkflowAdapter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean dispatchRun(String runId, String triggerStepId, Map<String, Object> resumeInput) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        ExecutionRunEntity run = executionRunService.findByRunId(runId);
        if (run == null) {
            return false;
        }
        List<ExecutionStepRunEntity> stepRuns = stepClaimService.listByRunId(runId);
        if (stepRuns == null || stepRuns.isEmpty()) {
            return false;
        }
        int totalStepCount = stepRuns.size();
        Integer activePlanVersion = run.getActivePlanVersion();
        if (activePlanVersion != null && activePlanVersion > 0) {
            stepRuns = stepRuns.stream()
                    .filter(step -> step != null
                            && step.getPlanVersion() != null
                            && step.getPlanVersion().equals(activePlanVersion))
                    .toList();
            int filteredOut = Math.max(0, totalStepCount - stepRuns.size());
            log.info("[execution-dispatch] 计划版本过滤结果|runId={} |activePlanVersion={} |matchedSteps={} |filteredOutSteps={}",
                    runId, activePlanVersion, stepRuns.size(), filteredOut);
            if (stepRuns.isEmpty()) {
                log.warn("[execution-dispatch] 当前激活计划版本无可调度步骤|runId={} |activePlanVersion={}",
                        runId, activePlanVersion);
                return false;
            }
        }

        WorkflowDefinition workflow = rebuildWorkflow(run, stepRuns, triggerStepId, resumeInput);
        WorkflowContext context = buildContext(run, stepRuns);
        log.info("[execution-dispatch] 已重建运行并继续执行|runId={} |stepCount={} |triggerStepId={}",
                runId, stepRuns.size(), triggerStepId);
        try {
            workflowOrchestrator.executeWorkflow(workflow, context);
            return true;
        } catch (Exception e) {
            log.error("[execution-dispatch] 续跑派发失败|runId={} |triggerStepId={}", runId, triggerStepId, e);
            return false;
        }
    }

    /**
     * 将 Replan 生成的新计划应用到已有 run。
     * 步骤：
     * 1. 计算新计划版本（当前版本 + 1）；
     * 2. 调用 switchActivePlanVersionAndIncreaseReplanCount 将旧版 PENDING 步骤标为已废弃；
     * 3. 将新计划步骤写入 ExecutionStepRun；
     * 4. 重新派发 run 继续执行。
     */
    public boolean applyNewPlan(String runId, ExecutionPlan newPlan) {
        if (runId == null || runId.isBlank() || newPlan == null
                || newPlan.getSteps() == null || newPlan.getSteps().isEmpty()) {
            log.warn("[execution-dispatch] applyNewPlan 参数无效|runId={}", runId);
            return false;
        }

        ExecutionRunEntity run = executionRunService.findByRunId(runId);
        if (run == null) {
            log.warn("[execution-dispatch] applyNewPlan 找不到 run|runId={}", runId);
            return false;
        }

        int currentVersion = run.getActivePlanVersion() == null ? 1 : run.getActivePlanVersion();
        int newVersion = currentVersion + 1;

        boolean switched = executionRunService.switchActivePlanVersionAndIncreaseReplanCount(runId, newVersion);
        if (!switched) {
            log.warn("[execution-dispatch] applyNewPlan 版本切换失败（CAS 冲突）|runId={} |newVersion={}", runId, newVersion);
            return false;
        }

        WorkflowDefinition workflow = executionPlanWorkflowAdapter.toWorkflowDefinition(newPlan, TYPE_TOOL_MAP);
        List<WorkflowStep> steps = workflow.getSteps();
        if (steps == null || steps.isEmpty()) {
            log.warn("[execution-dispatch] applyNewPlan 新计划转换后步骤为空|runId={}", runId);
            return false;
        }

        stepClaimService.prepareStepRuns(
                runId,
                steps,
                executionPersistenceProperties.getRecovery().getMaxRecoveryAttempts(),
                executionPersistenceProperties.getRecovery().getMaxRecoveryAttempts(),
                newVersion
        );

        log.info("[execution-dispatch] 新计划已写入|runId={} |newVersion={} |stepCount={}", runId, newVersion, steps.size());
        return dispatchRun(runId, null, null);
    }

    private WorkflowDefinition rebuildWorkflow(ExecutionRunEntity run,
                                               List<ExecutionStepRunEntity> stepRuns,
                                               String triggerStepId,
                                               Map<String, Object> resumeInput) {
        Set<String> activeStepIds = new HashSet<>();
        for (ExecutionStepRunEntity stepRun : stepRuns) {
            if (stepRun != null && stepRun.getStepId() != null && !stepRun.getStepId().isBlank()) {
                activeStepIds.add(stepRun.getStepId());
            }
        }

        List<WorkflowStep> steps = new ArrayList<>();
        for (ExecutionStepRunEntity stepRun : stepRuns) {
            Map<String, Object> parameters = readMap(stepRun.getInputJson());
            if (triggerStepId != null
                    && triggerStepId.equals(stepRun.getStepId())
                    && resumeInput != null
                    && !resumeInput.isEmpty()) {
                parameters.putAll(resumeInput);
            }
            steps.add(WorkflowStep.builder()
                    .stepId(stepRun.getStepId())
                    .capabilityName(resolveCapability(stepRun))
                    .dependsOn(filterActiveDependencies(stepRun, activeStepIds))
                    .parameters(parameters)
                    .sideEffect(stepRun.getSideEffect())
                    .failurePolicy(buildFailurePolicy(stepRun))
                    .build());
        }
        return WorkflowDefinition.builder()
                .id(run.getPlanId() == null || run.getPlanId().isBlank() ? "RUN-" + run.getRunId() : run.getPlanId())
                .name("Recovered-Run-" + run.getRunId())
                .steps(steps)
                .metadata(Map.of(
                        "source", "execution_step_run",
                        "recoveryMode", true,
                        "planVersion", run.getActivePlanVersion() == null ? 1 : run.getActivePlanVersion()
                ))
                .build();
    }

    /**
     * 仅保留 active plan 内部依赖，阻断旧计划输出对新计划下游的影响。
     */
    private List<String> filterActiveDependencies(ExecutionStepRunEntity stepRun, Set<String> activeStepIds) {
        List<String> original = readStringList(stepRun.getDependsOnJson());
        if (original.isEmpty() || activeStepIds == null || activeStepIds.isEmpty()) {
            return original;
        }
        List<String> filtered = original.stream()
                .filter(activeStepIds::contains)
                .toList();
        if (filtered.size() != original.size()) {
            log.info("[execution-dispatch] 已过滤跨版本依赖|runId={} |stepId={} |originDepends={} |filteredDepends={}",
                    stepRun.getRunId(), stepRun.getStepId(), original, filtered);
        }
        return filtered;
    }

    private WorkflowContext buildContext(ExecutionRunEntity run, List<ExecutionStepRunEntity> stepRuns) {
        WorkflowContext context = WorkflowContext.builder()
                .sessionId(run.getSessionId())
                .runId(run.getRunId())
                .workerId(executionPersistenceProperties.getPersistence().getWorkerId())
                .recoveryMode(true)
                .query(extractField(stepRuns, "query"))
                .taskFamily(extractField(stepRuns, "taskFamily"))
                .build();
        context.putAttribute("turnId", run.getTurnId());
        context.putAttribute("workflow.resume.dispatch", true);
        return context;
    }

    private FailurePolicy buildFailurePolicy(ExecutionStepRunEntity stepRun) {
        List<String> fallbackTools = readStringList(stepRun.getFallbackToolsJson());
        ExecutionEnums.ResumeMode resumeMode = parseResumeMode(stepRun.getResumeMode());
        boolean hasPolicy = (stepRun.getReplanAllowed() != null)
                || (stepRun.getNeedUserInputOnFailure() != null)
                || (resumeMode != null)
                || !fallbackTools.isEmpty();
        if (!hasPolicy) {
            return null;
        }
        return FailurePolicy.builder()
                .fallbackTools(fallbackTools)
                .replanAllowed(stepRun.getReplanAllowed() == null || stepRun.getReplanAllowed())
                .needUserInputOnFailure(Boolean.TRUE.equals(stepRun.getNeedUserInputOnFailure()))
                .resumeMode(resumeMode == null ? ExecutionEnums.ResumeMode.CONTINUE : resumeMode)
                .build();
    }

    private ExecutionEnums.ResumeMode parseResumeMode(String resumeMode) {
        if (resumeMode == null || resumeMode.isBlank()) {
            return null;
        }
        try {
            return ExecutionEnums.ResumeMode.valueOf(resumeMode);
        } catch (Exception e) {
            log.warn("[execution-dispatch] unknown resumeMode, fallback to CONTINUE|value={}", resumeMode);
            return ExecutionEnums.ResumeMode.CONTINUE;
        }
    }

    private String resolveCapability(ExecutionStepRunEntity stepRun) {
        if (stepRun.getSelectedTool() != null && !stepRun.getSelectedTool().isBlank()) {
            return stepRun.getSelectedTool();
        }
        if (stepRun.getActiveCapabilityName() != null && !stepRun.getActiveCapabilityName().isBlank()) {
            return stepRun.getActiveCapabilityName();
        }
        return stepRun.getCapabilityName();
    }

    private String extractField(List<ExecutionStepRunEntity> stepRuns, String key) {
        if (stepRuns == null || key == null || key.isBlank()) {
            return "";
        }
        for (ExecutionStepRunEntity stepRun : stepRuns) {
            Map<String, Object> input = readMap(stepRun.getInputJson());
            if (!input.containsKey(key)) {
                continue;
            }
            Object value = input.get(key);
            if (value != null) {
                String text = String.valueOf(value);
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private Map<String, Object> readMap(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(inputJson, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("[execution-dispatch] inputJson parse failed, fallback to empty map|error={}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private List<String> readStringList(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return List.of();
        }
        try {
            List<Object> raw = objectMapper.readValue(inputJson, new TypeReference<>() {
            });
            List<String> normalized = new ArrayList<>();
            for (Object item : raw) {
                if (item == null) {
                    continue;
                }
                String value = String.valueOf(item).trim();
                if (!value.isBlank()) {
                    normalized.add(value);
                }
            }
            return normalized;
        } catch (Exception e) {
            log.warn("[execution-dispatch] list json parse failed, fallback to empty list|error={}", e.getMessage());
            return List.of();
        }
    }
}
