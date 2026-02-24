package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.state.PlannerState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 资源估算节点
 * 评估每个子任务的时间与工具需求
 */
@Slf4j
@Component
public class ResourceEstimationNode {

    private static final Map<String, Integer> TYPE_TIME_MAP = Map.of(
            "SEARCH", 2,
            "RETRIEVE", 3,
            "SUMMARIZE", 5,
            "COMPARE", 7,
            "ANALYZE", 10,
            "TIMELINE", 8,
            "DEEP_DIVE", 10
    );

    private static final Map<String, List<String>> TYPE_TOOLS_MAP = Map.of(
            "SEARCH", List.of("search_news"),
            "RETRIEVE", List.of("retrieve_news", "rerank_results"),
            "SUMMARIZE", List.of("llm_generate"),
            "COMPARE", List.of("llm_generate"),
            "ANALYZE", List.of("llm_generate"),
            "TIMELINE", List.of("llm_generate"),
            "DEEP_DIVE", List.of("llm_generate")
    );

    /**
     * 执行资源估算
     *
     * @param state Planner状态
     * @return 更新后的状态
     */
    public PlannerState execute(PlannerState state) {
        state.incrementStep();

        List<PlannerState.SubTask> subTasks = state.getSubTasks();
        if (subTasks == null) {
            return state;
        }

        for (PlannerState.SubTask task : subTasks) {
            Integer estimated = TYPE_TIME_MAP.getOrDefault(task.getType(), 5);
            List<String> tools = TYPE_TOOLS_MAP.getOrDefault(task.getType(), List.of());
            task.setEstimatedTime(estimated);
            task.setRequiredTools(tools);
        }

        return state;
    }
}