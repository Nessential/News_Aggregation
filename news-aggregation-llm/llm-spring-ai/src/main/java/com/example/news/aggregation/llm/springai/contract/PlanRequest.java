package com.example.news.aggregation.llm.springai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

import com.example.news.aggregation.llm.springai.state.PlannerState;

/**
 * 计划请求
 * 由 Router 结果与上下文驱动 PlannerGraph 生成 Plan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanRequest {

    /** 用户查询 */
    private String query;

    /** Router 输出结果 */
    private RouterResult routerResult;

    /** 额外上下文（可选） */
    private Map<String, Object> context;

    /** 计划结构版本（可选） */
    private String planSchema;

    /** 语义版本（可选） */
    private String semanticVersion;

    /** 是否为 Replan 请求（true 时 TaskDecompositionNode 会注入失败上下文） */
    private Boolean isReplan;

    /** 触发 Replan 的原因描述（注入 LLM prompt） */
    private String replanReason;

    /** 已完成步骤的执行摘要（注入 LLM prompt，让 LLM 据此修正计划） */
    private Map<String, PlannerState.StepExecutionResult> stepResults;
}
