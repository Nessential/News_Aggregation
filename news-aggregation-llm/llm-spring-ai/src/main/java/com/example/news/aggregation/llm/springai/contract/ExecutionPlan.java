package com.example.news.aggregation.llm.springai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 统一执行计划契约。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionPlan {
    private String planId;
    /** 计划版本。Week5 用于局部重规划谱系追踪。 */
    private Integer planVersion;
    /** 父计划ID。首个计划可为空。 */
    private String parentPlanId;
    /** 本次重规划触发原因（非重规划场景可为空）。 */
    private String replanReasonCode;
    /** Planner 追踪ID，用于串联 Planner -> Executor -> Replay 全链路。 */
    private String plannerTraceId;
    private String goal;
    private String schemaVersion;
    private String semanticVersion;
    private List<ExecutionStep> steps;
    private List<ExecutionEdge> edges;
    private ExecutionConstraints constraints;
    private Map<String, Object> metadata;
}
