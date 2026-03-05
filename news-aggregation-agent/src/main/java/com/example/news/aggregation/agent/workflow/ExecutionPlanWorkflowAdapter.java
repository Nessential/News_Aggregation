package com.example.news.aggregation.agent.workflow;

import com.example.news.aggregation.llm.springai.contract.ExecutionPlan;
import com.example.news.aggregation.llm.springai.contract.ExecutionStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 执行计划到工作流定义的适配器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionPlanWorkflowAdapter {

    private final StepSemanticMapper stepSemanticMapper;

    /**
     * 转换 ExecutionPlan 为 WorkflowDefinition。
     * 关键职责：
     * 1. 为每个步骤解析 capability（优先使用 step.tool）；
     * 2. 将输入参数、语义字段(sideEffect/doneCheck)注入 WorkflowStep；
     * 3. 生成工作流元数据，便于审计与排障。
     */
    public WorkflowDefinition toWorkflowDefinition(ExecutionPlan plan, Map<String, List<String>> typeToolMap) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            log.warn("[adapter] ExecutionPlan 为空，返回空工作流");
            return WorkflowDefinition.builder()
                    .id("EXECUTION_PLAN_WORKFLOW_EMPTY")
                    .name("空执行计划工作流")
                    .steps(List.of())
                    .metadata(Map.of("source", "planner"))
                    .build();
        }
        log.info("[adapter] 开始转换计划：planId={}, stepCount={}",
                plan.getPlanId(), plan.getSteps().size());

        List<WorkflowStep> steps = new ArrayList<>();
        for (ExecutionStep step : plan.getSteps()) {
            if (step == null || step.getStepId() == null || step.getStepId().isBlank()) {
                log.warn("[adapter] 跳过非法步骤：step 为空或 stepId 为空");
                continue;
            }
            String capability = resolveCapability(step, typeToolMap);
            if (capability == null || capability.isBlank()) {
                log.warn("[adapter] 步骤未解析到能力，已跳过：stepId={}, stepType={}",
                        step.getStepId(), step.getType());
                continue;
            }

            Map<String, Object> parameters = new HashMap<>();
            if (step.getInput() != null) {
                parameters.putAll(step.getInput());
            }
            parameters.put("stepId", step.getStepId());
            parameters.put("stepType", step.getType());

            steps.add(WorkflowStep.builder()
                    .stepId(step.getStepId())
                    .capabilityName(capability)
                    .dependsOn(step.getDependsOn() != null ? step.getDependsOn() : List.of())
                    .parameters(parameters)
                    .sideEffect(stepSemanticMapper.resolveSideEffect(step))
                    .doneCheckRef(stepSemanticMapper.resolveDoneCheckRef(step))
                    .outputSchema(step.getOutputSchema())
                    .doneCheck(step.getDoneCheck())
                    .schemaVersion(plan.getSchemaVersion())
                    .semanticVersion(plan.getSemanticVersion())
                    .retryPolicy(step.getRetryPolicy())
                    .failurePolicy(step.getFailurePolicy())
                    .build());
            log.info("[adapter] 步骤转换完成：stepId={}, stepType={}, capability={}, dependsOn={}",
                    step.getStepId(),
                    step.getType(),
                    capability,
                    step.getDependsOn() != null ? step.getDependsOn() : List.of());
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "execution-plan");
        metadata.put("stepCount", steps.size());
        metadata.put("planId", plan.getPlanId());
        metadata.put("planVersion", plan.getPlanVersion() == null ? 1 : plan.getPlanVersion());
        metadata.put("parentPlanId", plan.getParentPlanId());
        metadata.put("replanReasonCode", plan.getReplanReasonCode());
        metadata.put("generatedAt", LocalDateTime.now().toString());
        if (plan.getMetadata() != null) {
            metadata.putAll(plan.getMetadata());
        }
        log.info("[adapter] 计划转换结束：planId={}, workflowStepCount={}", plan.getPlanId(), steps.size());

        return WorkflowDefinition.builder()
                .id("EXECUTION_PLAN_WORKFLOW_" + System.currentTimeMillis())
                .name("ExecutionPlan 生成工作流")
                .steps(steps)
                .metadata(metadata)
                .build();
    }

    private String resolveCapability(ExecutionStep step, Map<String, List<String>> typeToolMap) {
        if (step.getTool() != null && !step.getTool().isBlank()) {
            log.debug("[adapter] 使用步骤显式工具：stepId={}, tool={}", step.getStepId(), step.getTool());
            return step.getTool();
        }
        if (typeToolMap == null || step.getType() == null) {
            return null;
        }
        List<String> tools = typeToolMap.get(step.getType());
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        String resolved = tools.stream().filter(Objects::nonNull).findFirst().orElse(null);
        log.debug("[adapter] 使用类型映射工具：stepId={}, stepType={}, tool={}",
                step.getStepId(), step.getType(), resolved);
        return resolved;
    }
}
