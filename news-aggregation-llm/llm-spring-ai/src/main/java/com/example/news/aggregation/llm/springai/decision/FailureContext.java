package com.example.news.aggregation.llm.springai.decision;

import com.example.news.aggregation.llm.springai.contract.ExecutionEnums;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 决策输入上下文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailureContext {
    private ExecutionEnums.ErrorCategory errorCategory;
    private Integer retryCount;
    private Integer maxRetries;
    private boolean hasFallbackTool;
    private boolean replanAllowed;
    private boolean needsExternalSignal;
    private ExecutionEnums.SideEffectType sideEffect;
    private ExecutionEnums.EffectState effectState;
    private ExecutionEnums.ResumeMode preferredResumeMode;
}

