package com.example.news.aggregation.agent.tool;

/**
 * Tool 顶层接口。
 * 定义所有工具的通用契约。
 */
public interface Tool<I, O> {

    /**
     * 执行工具。
     *
     * @param input 输入参数
     * @return 输出结果
     */
    O execute(I input);

    /**
     * 获取工具名称。
     */
    String getName();

    /**
     * 获取工具描述。
     */
    String getDescription();
}