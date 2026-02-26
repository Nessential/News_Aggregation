package com.example.news.aggregation.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 工作流定义。
 * 由多个步骤组成。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowDefinition {

    /** 工作流 ID */
    private String id;

    /** 工作流名称 */
    private String name;

    /** 工作流步骤 */
    private List<WorkflowStep> steps;

    /** 元数据 */
    private Map<String, Object> metadata;
}