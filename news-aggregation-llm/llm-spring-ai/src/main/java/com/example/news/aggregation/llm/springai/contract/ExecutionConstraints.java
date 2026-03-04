package com.example.news.aggregation.llm.springai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 计划执行约束。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionConstraints {
    private Integer maxSteps;
    private Integer maxToolCalls;
    private Integer maxTokens;
    private Long timeoutMs;
}

