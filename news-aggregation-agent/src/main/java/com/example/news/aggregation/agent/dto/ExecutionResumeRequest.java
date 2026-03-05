package com.example.news.aggregation.agent.dto;

import lombok.Data;

import java.util.Map;

/**
 * 执行恢复请求。
 */
@Data
public class ExecutionResumeRequest {

    /**
     * 要恢复的步骤ID；为空时默认使用 run.currentStep。
     */
    private String stepId;

    /**
     * 用户补充输入。
     */
    private Map<String, Object> resumeInput;
}

