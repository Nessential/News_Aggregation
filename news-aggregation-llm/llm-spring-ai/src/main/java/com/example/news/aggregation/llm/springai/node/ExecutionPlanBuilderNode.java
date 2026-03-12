package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.contract.DoneCheckRule;
import com.example.news.aggregation.llm.springai.contract.ExecutionConstraints;
import com.example.news.aggregation.llm.springai.contract.ExecutionEdge;
import com.example.news.aggregation.llm.springai.contract.ExecutionEnums;
import com.example.news.aggregation.llm.springai.contract.ExecutionPlan;
import com.example.news.aggregation.llm.springai.contract.ExecutionStep;
import com.example.news.aggregation.llm.springai.contract.FailurePolicy;
import com.example.news.aggregation.llm.springai.contract.RetryPolicy;
import com.example.news.aggregation.llm.springai.state.PlannerState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 执行计划构建节点。
 * <p>
 * 该节点负责将子任务列表和依赖关系转换为统一的 {@link ExecutionPlan}，
 * 供 agent 执行层直接消费。
 */
@Slf4j
@Component
public class ExecutionPlanBuilderNode {

    public PlannerState execute(PlannerState state) {
        state.incrementStep();

        List<PlannerState.SubTask> subTasks = state.getSubTasks();
        Map<String, List<String>> dependencies = state.getDependencies();
        log.info("[执行计划构建] 开始构建执行计划。query={}，subTaskCount={}，hasDependencies={}",
                state.getQuery(),
                subTasks == null ? 0 : subTasks.size(),
                dependencies != null && !dependencies.isEmpty());

        if (subTasks == null || subTasks.isEmpty()) {
            log.warn("[执行计划构建] 子任务为空，返回空执行计划。query={}", state.getQuery());
            state.setExecutionPlan(emptyPlan(state));
            return state;
        }

        List<String> executionOrder = buildExecutionOrder(subTasks, dependencies);
        Map<String, PlannerState.SubTask> subTaskMap = new LinkedHashMap<>();
        for (PlannerState.SubTask subTask : subTasks) {
            if (subTask != null && subTask.getId() != null) {
                subTaskMap.put(subTask.getId(), subTask);
            }
        }

        List<ExecutionStep> steps = new ArrayList<>();
        List<ExecutionEdge> edges = new ArrayList<>();
        for (String taskId : executionOrder) {
            PlannerState.SubTask subTask = subTaskMap.get(taskId);
            if (subTask == null) {
                continue;
            }

            List<String> dependsOn = subTask.getDependencies() != null ? subTask.getDependencies() : List.of();
            steps.add(ExecutionStep.builder()
                    .stepId(subTask.getId())
                    .name(subTask.getDescription())
                    .type(subTask.getType())
                    .tool(resolveTool(subTask))
                    .dependsOn(dependsOn)
                    .input(subTask.getParameters())
                    .outputSchema(defaultOutputSchema(subTask.getType()))
                    .doneCheck(defaultDoneCheck(subTask.getType()))
                    .sideEffect(resolveSideEffect(subTask))
                    .retryPolicy(defaultRetryPolicy(subTask))
                    .failurePolicy(defaultFailurePolicy())
                    .timeoutMs(resolveTimeout(subTask))
                    .build());

            for (String dependency : dependsOn) {
                edges.add(ExecutionEdge.builder()
                        .fromStepId(dependency)
                        .toStepId(subTask.getId())
                        .condition("on_success")
                        .build());
            }
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("taskCount", steps.size());
        metadata.put("executionOrder", executionOrder);
        metadata.put("parallelizable", isParallelizable(dependencies));

        ExecutionPlan plan = ExecutionPlan.builder()
                .planId("plan-" + UUID.randomUUID().toString().replace("-", ""))
                .plannerTraceId("trace-" + UUID.randomUUID().toString().replace("-", ""))
                .goal(state.getQuery())
                .schemaVersion("execution-plan/1.0")
                .semanticVersion(state.getSemanticVersion() != null ? state.getSemanticVersion() : "1.0.0")
                .steps(steps)
                .edges(edges)
                .constraints(ExecutionConstraints.builder()
                        .maxSteps(20)
                        .maxToolCalls(30)
                        .maxTokens(8000)
                        .timeoutMs(120000L)
                        .build())
                .metadata(metadata)
                .build();

        log.info("[执行计划构建] 构建完成。planId={}，traceId={}，stepCount={}，edgeCount={}，executionOrder={}",
                plan.getPlanId(), plan.getPlannerTraceId(), steps.size(), edges.size(), executionOrder);
        log.info("[执行计划构建] 最终执行步骤摘要={}", summarizeSteps(steps));
        state.setExecutionPlan(plan);
        return state;
    }

    private ExecutionPlan emptyPlan(PlannerState state) {
        return ExecutionPlan.builder()
                .planId("plan-empty")
                .goal(state.getQuery())
                .schemaVersion("execution-plan/1.0")
                .semanticVersion(state.getSemanticVersion() != null ? state.getSemanticVersion() : "1.0.0")
                .steps(List.of())
                .edges(List.of())
                .constraints(ExecutionConstraints.builder()
                        .maxSteps(0)
                        .maxToolCalls(0)
                        .maxTokens(0)
                        .timeoutMs(0L)
                        .build())
                .metadata(Map.of("taskCount", 0))
                .build();
    }

    private String resolveTool(PlannerState.SubTask subTask) {
        if (subTask.getRequiredTools() != null && !subTask.getRequiredTools().isEmpty()) {
            String tool = subTask.getRequiredTools().get(0);
            log.debug("[执行计划构建] 使用模型指定工具。taskId={}，taskType={}，tool={}",
                    subTask.getId(), subTask.getType(), tool);
            return tool;
        }

        String taskType = subTask.getType();
        String resolved = switch (taskType) {
            case "SEARCH" -> "search_news";
            case "RETRIEVE" -> "retrieve_news";
            case "RERANK" -> "rerank_results";
            default -> "llm_generate";
        };
        log.debug("[执行计划构建] 使用默认工具映射。taskId={}，taskType={}，tool={}",
                subTask.getId(), taskType, resolved);
        return resolved;
    }

    private Map<String, Object> defaultOutputSchema(String taskType) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        if ("SEARCH".equals(taskType) || "RETRIEVE".equals(taskType) || "RERANK".equals(taskType)) {
            schema.put("required", List.of("items"));
        } else {
            schema.put("required", List.of("answer"));
        }
        return schema;
    }

    private DoneCheckRule defaultDoneCheck(String taskType) {
        if ("SEARCH".equals(taskType) || "RETRIEVE".equals(taskType) || "RERANK".equals(taskType)) {
            return DoneCheckRule.builder()
                    .requiredFields(List.of("items"))
                    .minEvidenceCount(1)
                    .expression("items_count >= 1")
                    .build();
        }
        return DoneCheckRule.builder()
                .requiredFields(List.of("answer"))
                .minEvidenceCount(1)
                .expression("answer_not_blank")
                .build();
    }

    private RetryPolicy defaultRetryPolicy(PlannerState.SubTask subTask) {
        boolean readOnly = resolveSideEffect(subTask) != ExecutionEnums.SideEffectType.WRITE;
        return RetryPolicy.builder()
                .maxRetries(readOnly ? 2 : 1)
                .backoffMs(readOnly ? 300L : 1000L)
                .retryableErrorCodes(List.of("TIMEOUT", "NETWORK"))
                .build();
    }

    private FailurePolicy defaultFailurePolicy() {
        return FailurePolicy.builder()
                .fallbackTools(List.of())
                .replanAllowed(true)
                .needUserInputOnFailure(false)
                .resumeMode(ExecutionEnums.ResumeMode.CONTINUE)
                .build();
    }

    private ExecutionEnums.SideEffectType resolveSideEffect(PlannerState.SubTask subTask) {
        if ("SEARCH".equals(subTask.getType()) || "RETRIEVE".equals(subTask.getType()) || "RERANK".equals(subTask.getType())) {
            return ExecutionEnums.SideEffectType.READ;
        }
        return ExecutionEnums.SideEffectType.NONE;
    }

    private Long resolveTimeout(PlannerState.SubTask subTask) {
        Integer estimated = subTask.getEstimatedTime() != null ? subTask.getEstimatedTime() : 5;
        return estimated * 1000L * 2;
    }

    private boolean isParallelizable(Map<String, List<String>> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            return true;
        }
        int independentCount = 0;
        for (List<String> dependency : dependencies.values()) {
            if (dependency == null || dependency.isEmpty()) {
                independentCount++;
            }
        }
        return independentCount >= 2;
    }

    private List<String> buildExecutionOrder(List<PlannerState.SubTask> subTasks,
                                             Map<String, List<String>> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            List<String> order = new ArrayList<>();
            for (PlannerState.SubTask task : subTasks) {
                order.add(task.getId());
            }
            return order;
        }
        return topologicalSort(dependencies);
    }

    private List<String> topologicalSort(Map<String, List<String>> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (String node : dependencies.keySet()) {
            indegree.put(node, 0);
            adjacency.putIfAbsent(node, new ArrayList<>());
        }

        for (Map.Entry<String, List<String>> entry : dependencies.entrySet()) {
            String taskId = entry.getKey();
            List<String> deps = entry.getValue();
            if (deps == null) {
                continue;
            }
            for (String dep : deps) {
                adjacency.putIfAbsent(dep, new ArrayList<>());
                adjacency.get(dep).add(taskId);
                indegree.put(taskId, indegree.getOrDefault(taskId, 0) + 1);
            }
        }

        ArrayDeque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String node = queue.poll();
            order.add(node);
            for (String next : adjacency.getOrDefault(node, List.of())) {
                indegree.put(next, indegree.get(next) - 1);
                if (indegree.get(next) == 0) {
                    queue.add(next);
                }
            }
        }

        if (order.size() != indegree.size()) {
            log.warn("[执行计划构建] 检测到循环依赖，退回到原始任务顺序。");
            return new ArrayList<>(dependencies.keySet());
        }
        return order;
    }

    private List<Map<String, Object>> summarizeSteps(List<ExecutionStep> steps) {
        List<Map<String, Object>> summary = new ArrayList<>();
        if (steps == null || steps.isEmpty()) {
            return summary;
        }
        for (ExecutionStep step : steps) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stepId", step.getStepId());
            item.put("type", step.getType());
            item.put("tool", step.getTool());
            item.put("dependsOn", step.getDependsOn());
            item.put("input", step.getInput());
            summary.add(item);
        }
        return summary;
    }
}
