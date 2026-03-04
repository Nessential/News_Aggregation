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
    private String goal;
    private String schemaVersion;
    private String semanticVersion;
    private List<ExecutionStep> steps;
    private List<ExecutionEdge> edges;
    private ExecutionConstraints constraints;
    private Map<String, Object> metadata;
}

