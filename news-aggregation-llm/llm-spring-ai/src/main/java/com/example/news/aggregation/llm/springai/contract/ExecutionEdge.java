package com.example.news.aggregation.llm.springai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 执行计划依赖边。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionEdge {
    private String fromStepId;
    private String toStepId;
    private String condition;
}

