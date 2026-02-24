package com.example.news.aggregation.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 工作流步骤。
 * 绑定能力名称与参数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowStep {

    /** 步骤ID */
    private String stepId;

    /** 能力名称 */
    private String capabilityName;

    /** 依赖的步骤ID列表 */
    private List<String> dependsOn;

    /** 步骤参数 */
    private Map<String, Object> parameters;
}