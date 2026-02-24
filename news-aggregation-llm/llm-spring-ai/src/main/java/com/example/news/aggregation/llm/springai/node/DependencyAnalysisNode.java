package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.state.PlannerState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 依赖分析节点
 * 构建子任务之间的依赖关系
 */
@Slf4j
@Component
public class DependencyAnalysisNode {

    /**
     * 执行依赖分析
     *
     * @param state Planner状态
     * @return 更新后的状态
     */
    public PlannerState execute(PlannerState state) {
        state.incrementStep();

        List<PlannerState.SubTask> subTasks = state.getSubTasks();
        Map<String, List<String>> dependencies = new HashMap<>();

        if (subTasks == null) {
            state.setDependencies(dependencies);
            return state;
        }

        for (PlannerState.SubTask task : subTasks) {
            List<String> deps = extractDependencies(task);
            task.setDependencies(deps);
            dependencies.put(task.getId(), deps);
        }

        state.setDependencies(dependencies);
        return state;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractDependencies(PlannerState.SubTask task) {
        List<String> deps = new ArrayList<>();
        if (task.getDependencies() != null) {
            deps.addAll(task.getDependencies());
        }
        Map<String, Object> params = task.getParameters();
        if (params != null && params.containsKey("sources")) {
            Object sources = params.get("sources");
            if (sources instanceof List) {
                deps.addAll((List<String>) sources);
            }
        }
        return deps;
    }
}