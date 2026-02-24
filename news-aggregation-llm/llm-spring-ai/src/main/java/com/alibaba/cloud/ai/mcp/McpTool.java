package com.alibaba.cloud.ai.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MCP工具注解（占位实现）
 * 当前用于本地编译与工具标注，后续应替换为Spring AI Alibaba官方实现
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpTool {

    /** 工具名称 */
    String name();

    /** 工具描述 */
    String description() default "";
}
