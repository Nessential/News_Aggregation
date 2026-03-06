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

    /** 是否由 Replan 触发（控制 TaskDecompositionNode 是否注入失败上下文到 prompt） */
    private boolean isReplan;

    /** 触发 Replan 的原因（写入 LLM prompt，让 LLM 据此修正计划） */
    private String replanReason;

    /** 已完成步骤的执行结果摘要（stepId -> 结果），Replan 时注入 LLM prompt */
    private Map<String, StepExecutionResult> stepResults;

    /** 步数+1 */
    public void incrementStep() {
        this.stepCount++;
    }

    /**
     * 步骤执行结果摘要（精简，避免 token 爆炸）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepExecutionResult {
        /** 步骤 ID */
        private String stepId;
        /** 执行状态：SUCCESS / FAILED */
        private String status;
        /** 实际使用的工具名 */
        private String toolUsed;
        /** 输出精简摘要（如"找到 8 篇文章"），不传完整数据 */
        private String outputSummary;
        /** 失败原因（status=FAILED 时填写） */
        private String failureReason;
        /** 检索到的证据数量 */
        private int evidenceCount;
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
