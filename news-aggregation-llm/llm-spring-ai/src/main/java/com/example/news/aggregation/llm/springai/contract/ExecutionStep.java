package com.example.news.aggregation.llm.springai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 执行计划步骤定义。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionStep {
    private String stepId;
    private String name;
    private String type;
    private String tool;
    private List<String> dependsOn;
    private Map<String, Object> input;
    private Map<String, Object> outputSchema;
    private DoneCheckRule doneCheck;
    private ExecutionEnums.SideEffectType sideEffect;
    private RetryPolicy retryPolicy;
    private FailurePolicy failurePolicy;
    private Long timeoutMs;
}

