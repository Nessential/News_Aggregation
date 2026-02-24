package com.example.news.aggregation.agent.workflow;

import com.example.news.aggregation.llm.springai.contract.Plan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 计划到工作流的适配器。
 * 将 Planner 输出的 Plan 转换为可执行的 WorkflowDefinition。
 */
@Slf4j
@Component
public class PlanWorkflowAdapter {

    /**
     * 转换 Plan 为 WorkflowDefinition。
     *
     * @param plan 计划
     * @param typeToolMap 任务类型到工具列表映射
     * @return 工作流定义
     */
    public WorkflowDefinition toWorkflowDefinition(Plan plan, Map<String, List<String>> typeToolMap) {
        if (plan == null || plan.getTasks() == null || plan.getTasks().isEmpty()) {
            return WorkflowDefinition.builder()
                    .id("PLAN_WORKFLOW_EMPTY")
                    .name("空计划工作流")
                    .steps(List.of())
                    .metadata(Map.of("source", "planner"))
                    .build();
        }

        Map<String, Plan.Task> taskMap = new HashMap<>();
        for (Plan.Task task : plan.getTasks()) {
            if (task != null && task.getId() != null) {
                taskMap.put(task.getId(), task);
            }
        }

        List<String> executionOrder = plan.getExecutionOrder();
        if (executionOrder == null || executionOrder.isEmpty()) {
            executionOrder = new ArrayList<>(taskMap.keySet());
        }

        Map<String, String> taskLastStep = new HashMap<>();
        List<WorkflowStep> steps = new ArrayList<>();

        for (String taskId : executionOrder) {
            Plan.Task task = taskMap.get(taskId);
            if (task == null) {
                continue;
            }
            List<String> tools = resolveTools(task, typeToolMap);
            if (tools.isEmpty()) {
                log.warn("No tools for task: {}", taskId);
                continue;
            }

            String previousStepId = null;
            int index = 0;
            for (String tool : tools) {
                String stepId = taskId + ":" + tool + ":" + index;
                List<String> dependencies = new ArrayList<>();

                // 第一个工具继承任务依赖，其余工具依赖前一步
                if (previousStepId == null) {
                    List<String> taskDeps = task.getDependencies();
                    if (taskDeps != null) {
                        for (String depTaskId : taskDeps) {
                            String depStep = taskLastStep.get(depTaskId);
                            if (depStep != null) {
                                dependencies.add(depStep);
                            }
                        }
                    }
                } else {
                    dependencies.add(previousStepId);
                }

                WorkflowStep step = WorkflowStep.builder()
                        .stepId(stepId)
                        .capabilityName(tool)
                        .dependsOn(dependencies)
                        .parameters(task.getParameters())
                        .build();
                steps.add(step);

                previousStepId = stepId;
                index++;
            }

            if (previousStepId != null) {
                taskLastStep.put(taskId, previousStepId);
            }
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "planner");
        metadata.put("parallelizable", plan.getParallelizable());
        metadata.put("taskCount", plan.getTasks().size());
        metadata.put("generatedAt", LocalDateTime.now().toString());

        return WorkflowDefinition.builder()
                .id("PLAN_WORKFLOW_" + System.currentTimeMillis())
                .name("Planner 生成工作流")
                .steps(steps)
                .metadata(metadata)
                .build();
    }

    private List<String> resolveTools(Plan.Task task, Map<String, List<String>> typeToolMap) {
        if (task.getTools() != null && !task.getTools().isEmpty()) {
            return new ArrayList<>(task.getTools());
        }
        if (typeToolMap == null) {
            return List.of();
        }
        String type = task.getType();
        if (type == null) {
            return List.of();
        }
        List<String> tools = typeToolMap.get(type);
        if (tools == null) {
            return List.of();
        }
        return tools.stream().filter(Objects::nonNull).toList();
    }
}