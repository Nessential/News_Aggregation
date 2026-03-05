package com.example.news.aggregation.agent.execution.service;

import com.example.news.aggregation.agent.execution.config.ExecutionPersistenceProperties;
import com.example.news.aggregation.agent.execution.domain.ExecutionRunEntity;
import com.example.news.aggregation.agent.execution.domain.ExecutionStepRunEntity;
import com.example.news.aggregation.agent.workflow.WorkflowContext;
import com.example.news.aggregation.agent.workflow.WorkflowDefinition;
import com.example.news.aggregation.agent.workflow.WorkflowOrchestrator;
import com.example.news.aggregation.agent.workflow.WorkflowStep;
import com.example.news.aggregation.llm.springai.contract.ExecutionEnums;
import com.example.news.aggregation.llm.springai.contract.FailurePolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds workflow context from persisted run/step state and dispatches execution.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionDispatchService {

    private final ExecutionRunService executionRunService;
    private final StepClaimService stepClaimService;
    private final WorkflowOrchestrator workflowOrchestrator;
    private final ExecutionPersistenceProperties executionPersistenceProperties;

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

        WorkflowDefinition workflow = rebuildWorkflow(run, stepRuns, triggerStepId, resumeInput);
        WorkflowContext context = buildContext(run, stepRuns);
        log.info("[execution-dispatch] rebuild run and continue execution|runId={} |stepCount={} |triggerStepId={}",
                runId, stepRuns.size(), triggerStepId);
        try {
            workflowOrchestrator.executeWorkflow(workflow, context);
            return true;
        } catch (Exception e) {
            log.error("[execution-dispatch] dispatch failed|runId={} |triggerStepId={}", runId, triggerStepId, e);
            return false;
        }
    }

    private WorkflowDefinition rebuildWorkflow(ExecutionRunEntity run,
                                               List<ExecutionStepRunEntity> stepRuns,
                                               String triggerStepId,
                                               Map<String, Object> resumeInput) {
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
                    .dependsOn(readStringList(stepRun.getDependsOnJson()))
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
                        "recoveryMode", true
                ))
                .build();
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
