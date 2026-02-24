package com.example.news.aggregation.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 能力元数据。
 * 描述工具/能力的基础信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityMetadata {

    /** 能力名称 */
    private String name;

    /** 版本号 */
    private String version;

    /** 能力描述 */
    private String description;

    /** 输入 Schema（JSON 描述，可选） */
    private String inputSchema;

    /** 输出 Schema（JSON 描述，可选） */
    private String outputSchema;

    /** 超时毫秒 */
    private Long timeoutMs;

    /** 成本等级（LOW/MEDIUM/HIGH） */
    private String costLevel;

    /** 权限范围（PUBLIC/INTERNAL/ADMIN，可选） */
    private String permissionScope;
}