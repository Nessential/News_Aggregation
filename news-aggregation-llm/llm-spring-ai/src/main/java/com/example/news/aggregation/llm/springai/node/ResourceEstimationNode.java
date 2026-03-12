package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.config.PlannerResourceEstimationProperties;
import com.example.news.aggregation.llm.springai.state.PlannerState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 资源估算节点。
 * <p>
 * 节点会补充每个子任务的预计耗时和默认工具，
 * 但不会覆盖模型已经明确选定的工具。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceEstimationNode {

    private final PlannerResourceEstimationProperties properties;

    public PlannerState execute(PlannerState state) {
        state.incrementStep();

        List<PlannerState.SubTask> subTasks = state.getSubTasks();
        if (subTasks == null || subTasks.isEmpty()) {
            return state;
        }

        Map<String, Integer> typeTimeMap = properties.getTypeTimeMap();
        Map<String, List<String>> typeToolsMap = properties.getTypeToolsMap();

        for (PlannerState.SubTask task : subTasks) {
            String taskType = task.getType();
            Integer estimated = typeTimeMap != null ? typeTimeMap.get(taskType) : null;
            List<String> defaultTools = typeToolsMap != null ? typeToolsMap.get(taskType) : null;

            task.setEstimatedTime(estimated != null ? estimated : 5);
            if (task.getRequiredTools() == null || task.getRequiredTools().isEmpty()) {
                task.setRequiredTools(defaultTools != null ? defaultTools : List.of());
            }

            log.debug("[任务规划] 资源估算完成。taskId={}，taskType={}，estimatedTime={}，requiredTools={}",
                    task.getId(), taskType, task.getEstimatedTime(), task.getRequiredTools());
        }

        return state;
    }
}
