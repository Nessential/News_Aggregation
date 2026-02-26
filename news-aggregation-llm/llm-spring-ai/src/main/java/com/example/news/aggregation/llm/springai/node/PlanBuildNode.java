package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.contract.Plan;
import com.example.news.aggregation.llm.springai.state.PlannerState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 计划构建节点
 * 将子任务与依赖关系转换为Plan契约
 */
@Slf4j
@Component
public class PlanBuildNode {

    /**
     * 执行计划构建
     *
     * @param state Planner状态
     * @return 更新后的状态
     */
    public PlannerState execute(PlannerState state) {
        state.incrementStep();

        List<PlannerState.SubTask> subTasks = state.getSubTasks();
        Map<String, List<String>> dependencies = state.getDependencies();

        if (subTasks == null || subTasks.isEmpty()) {
            state.setPlan(Plan.builder().tasks(List.of()).build());
            return state;
        }

        List<String> executionOrder = buildExecutionOrder(subTasks, dependencies);
        int totalTime = calculateTotalTime(subTasks);

        List<Plan.Task> tasks = new ArrayList<>();
        for (PlannerState.SubTask subTask : subTasks) {
            tasks.add(Plan.Task.builder()
                    .id(subTask.getId())
                    .type(subTask.getType())
                    .description(subTask.getDescription())
                    .dependencies(subTask.getDependencies())
                    .tools(subTask.getRequiredTools())
                    .estimatedTime(subTask.getEstimatedTime())
                    .parameters(subTask.getParameters())
                    .build());
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("taskCount", tasks.size());

        Plan plan = Plan.builder()
                .tasks(tasks)
                .executionOrder(executionOrder)
                .totalEstimatedTime(totalTime)
                .parallelizable(isParallelizable(dependencies))
                .metadata(metadata)
                .build();

        state.setPlan(plan);
        return state;
    }

    private int calculateTotalTime(List<PlannerState.SubTask> tasks) {
        int total = 0;
        for (PlannerState.SubTask task : tasks) {
            total += task.getEstimatedTime() != null ? task.getEstimatedTime() : 0;
        }
        return total;
    }

    private boolean isParallelizable(Map<String, List<String>> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            return true;
        }
        int independentCount = 0;
        for (List<String> deps : dependencies.values()) {
            if (deps == null || deps.isEmpty()) {
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

    /**
     * 基于依赖关系进行拓扑排序
     */
    private List<String> topologicalSort(Map<String, List<String>> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();

        // 初始化

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

        // 若存在环，则返回原始顺序兜底

        if (order.size() != indegree.size()) {
            log.warn("Topological sort detected cycle, fallback to original order.");
            return new ArrayList<>(dependencies.keySet());
        }

        return order;
    }
}
