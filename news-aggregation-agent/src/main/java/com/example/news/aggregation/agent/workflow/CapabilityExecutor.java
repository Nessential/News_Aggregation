package com.example.news.aggregation.agent.workflow;

import java.util.Map;

/**
 * 能力执行器。
 * 为每个能力提供统一的执行入口。
 */
public interface CapabilityExecutor {

    /** 能力名称（唯一） */
    String capabilityName();

    /** 能力元数据 */
    CapabilityMetadata metadata();

    /**
     * 执行能力。
     *
     * @param parameters 输入参数
     * @param context    工作流上下文
     * @return 执行结果
     */
    Object execute(Map<String, Object> parameters, WorkflowContext context);
}