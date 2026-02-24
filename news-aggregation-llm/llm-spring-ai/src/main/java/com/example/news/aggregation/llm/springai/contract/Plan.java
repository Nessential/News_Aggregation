package com.example.news.aggregation.llm.springai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 计划契约
 * 表示 PlannerGraph 输出的可执行任务计划
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Plan {

    /** 任务列表 */
    private List<Task> tasks;

    /** 执行顺序（按任务ID排序后的拓扑结果） */
    private List<String> executionOrder;

    /** 总预估时间（秒） */
    private Integer totalEstimatedTime;

    /** 是否可并行执行 */
    private Boolean parallelizable;

    /** 元数据（如任务数、并行数等） */
    private Map<String, Object> metadata;

    /**
     * 计划中的单个任务
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Task {
        /** 任务ID */
        private String id;
        /** 任务类型：SEARCH/RETRIEVE/SUMMARIZE/COMPARE/ANALYZE/TIMELINE */
        private String type;
        /** 任务描述 */
        private String description;
        /** 依赖任务ID列表 */
        private List<String> dependencies;
        /** 需要调用的工具 */
        private List<String> tools;
        /** 预估执行时间（秒） */
        private Integer estimatedTime;
        /** 任务参数 */
        private Map<String, Object> parameters;
    }
}