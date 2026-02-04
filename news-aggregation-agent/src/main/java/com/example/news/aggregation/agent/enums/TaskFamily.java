package com.example.news.aggregation.agent.enums;

/**
 * 任务类型枚举
 * 用于Pipeline路由和执行策略选择
 */
public enum TaskFamily {
    /**
     * 问答任务 - 单轮问题回答
     */
    QA,
    
    /**
     * 总结任务 - 多文档总结
     */
    SUMMARY,
    
    /**
     * 对比任务 - 观点对比、趋势分析
     */
    COMPARE,
    
    /**
     * 时间线任务 - 事件演进
     */
    TIMELINE,
    
    /**
     * 深度分析任务
     */
    DEEP_DIVE,
    
    /**
     * 搜索任务 - 返回候选文章列表
     */
    SEARCH,
    
    /**
     * 监控任务 - 持续追踪
     */
    MONITORING
}
