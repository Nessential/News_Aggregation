package com.example.news.aggregation.llm.springai.decision;

import com.example.news.aggregation.llm.springai.contract.ExecutionEnums;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 决策输出结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionResult {
    private ExecutionEnums.DecisionAction action;
    private ExecutionEnums.NextState nextState;
    private String reasonCode;
    private ExecutionEnums.ResumeMode resumeMode;
}

