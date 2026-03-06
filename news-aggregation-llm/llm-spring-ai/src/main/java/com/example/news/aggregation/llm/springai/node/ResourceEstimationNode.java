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
 * 从配置读取 taskType 对应的耗时与工具，减少硬编码改动成本。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceEstimationNode {

    private final PlannerResourceEstimationProperties properties;

    /**
     * 执行资源估算。
     */
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
            List<String> tools = typeToolsMap != null ? typeToolsMap.get(taskType) : null;

            task.setEstimatedTime(estimated != null ? estimated : 5);
            task.setRequiredTools(tools != null ? tools : List.of());

            log.debug("[planner] 资源估算|taskId={} |taskType={} |estimatedTime={} |requiredTools={}",
                    task.getId(), taskType, task.getEstimatedTime(), task.getRequiredTools());
        }

        return state;
    }
}
