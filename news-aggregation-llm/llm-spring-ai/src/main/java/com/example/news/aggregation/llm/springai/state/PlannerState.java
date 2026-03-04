package com.example.news.aggregation.llm.springai.state;

import com.example.news.aggregation.llm.springai.contract.ExecutionPlan;
import com.example.news.aggregation.llm.springai.contract.RouterResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * PlannerGraph状态
 * 记录任务分解、依赖分析与计划生成的中间结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlannerState {

    /** 用户查询 */
    private String query;

    /** Router输出结果 */
    private RouterResult routerResult;

    /** 额外上下文 */
    private Map<String, Object> context;

    /** 子任务列表 */
    private List<SubTask> subTasks;

    /** 依赖关系（taskId -> 依赖taskId列表） */
    private Map<String, List<String>> dependencies;

    /** 生成的执行计划 */
    private ExecutionPlan executionPlan;

    /** 语义版本 */
    private String semanticVersion;

    /** 执行步数 */
    private int stepCount;

    /** 错误信息 */
    private String error;

    /** 步数+1 */
    public void incrementStep() {
        this.stepCount++;
    }

    /**
     * 子任务定义
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubTask {
        /** 任务ID */
        private String id;
        /** 任务类型 */
        private String type;
        /** 任务描述 */
        private String description;
        /** 依赖列表 */
        private List<String> dependencies;
        /** 需要的工具 */
        private List<String> requiredTools;
        /** 预估时间（秒） */
        private Integer estimatedTime;
        /** 参数 */
        private Map<String, Object> parameters;
    }
}
