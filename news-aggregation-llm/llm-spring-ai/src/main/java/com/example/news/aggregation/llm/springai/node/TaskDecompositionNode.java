package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.state.PlannerState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务分解节点
 * 将复杂查询拆分为可执行子任务
 */
@Slf4j
@Component
public class TaskDecompositionNode {

    /**
     * 执行任务分解
     *
     * @param state Planner状态
     * @return 更新后的状态
     */
    public PlannerState execute(PlannerState state) {
        state.incrementStep();

        String query = state.getQuery();
        String taskFamily = state.getRouterResult() != null ? state.getRouterResult().getTaskFamily() : "QA";
        Map<String, Object> filters = buildFilters(state);

        List<PlannerState.SubTask> subTasks = new ArrayList<>();

        if ("COMPARE".equals(taskFamily) || "TIMELINE".equals(taskFamily) || "DEEP_DIVE".equals(taskFamily)) {
            subTasks.add(PlannerState.SubTask.builder()
                    .id("task-1")
                    .type("SEARCH")
                    .description("关键词检索相关资料")
                    .parameters(buildParams(query, filters))
                    .build());
            subTasks.add(PlannerState.SubTask.builder()
                    .id("task-2")
                    .type("RETRIEVE")
                    .description("向量检索补充证据")
                    .parameters(buildParams(query, filters))
                    .build());
            subTasks.add(PlannerState.SubTask.builder()
                    .id("task-3")
                    .type(taskFamily.equals("COMPARE") ? "COMPARE"
                            : taskFamily.equals("TIMELINE") ? "TIMELINE" : "ANALYZE")
                    .description("基于证据进行综合分析")
                    .dependencies(List.of("task-1", "task-2"))
                    .parameters(buildGenerateParams(taskFamily))
                    .build());
        } else {
            subTasks.add(PlannerState.SubTask.builder()
                    .id("task-1")
                    .type("SEARCH")
                    .description("检索相关资料")
                    .parameters(buildParams(query, filters))
                    .build());
        }

        state.setSubTasks(subTasks);
        return state;
    }

    private Map<String, Object> buildParams(String query, Map<String, Object> filters) {
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        if (filters != null && !filters.isEmpty()) {
            params.put("filters", filters);
        }
        return params;
    }

    private Map<String, Object> buildGenerateParams(String taskFamily) {
        Map<String, Object> params = new HashMap<>();
        params.put("taskFamily", taskFamily);
        return params;
    }

    private Map<String, Object> buildFilters(PlannerState state) {
        Map<String, Object> filters = new HashMap<>();
        if (state.getRouterResult() != null && state.getRouterResult().getParams() != null) {
            Map<String, Object> params = state.getRouterResult().getParams();
            putIfPresent(filters, "timeRange", params, "timeRange", "time_range", "time-range");
            putIfPresent(filters, "startDate", params, "startDate", "start_date");
            putIfPresent(filters, "endDate", params, "endDate", "end_date");
            putIfPresent(filters, "keywords", params, "keywords");
            putIfPresent(filters, "topic", params, "topic");
            putIfPresent(filters, "category", params, "category");
            putIfPresent(filters, "language", params, "language", "lang");
            putIfPresent(filters, "region", params, "region");
            putIfPresent(filters, "source", params, "source");
            putIfPresent(filters, "publisher", params, "publisher");
            putIfPresent(filters, "sortBy", params, "sortBy", "sort_by");
        }
        if (state.getContext() != null) {
            Object contextFilters = state.getContext().get("filters");
            if (contextFilters instanceof Map<?, ?> map) {
                map.forEach((key, value) -> {
                    if (key != null && value != null) {
                        filters.put(String.valueOf(key), value);
                    }
                });
            }
        }
        return filters;
    }

    private void putIfPresent(Map<String, Object> target,
                              String normalizedKey,
                              Map<String, Object> source,
                              String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) {
                Object value = source.get(key);
                if (value != null) {
                    target.put(normalizedKey, value);
                    return;
                }
            }
        }
    }
}