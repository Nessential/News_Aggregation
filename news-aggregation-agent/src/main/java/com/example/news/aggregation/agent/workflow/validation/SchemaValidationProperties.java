package com.example.news.aggregation.agent.workflow.validation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具 Schema 校验配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.agent.schema-validation")
public class SchemaValidationProperties {

    /** 是否启用输入/输出校验。 */
    private boolean enabled = true;

    /** 校验模式，默认严格模式。 */
    private SchemaValidationMode mode = SchemaValidationMode.STRICT;

    /** 支持的 schema 版本列表。 */
    private List<String> supportedSchemaVersions = new ArrayList<>(List.of("execution-plan/1.0"));

    /** 默认 schema 版本（当步骤和上下文均未显式提供时使用）。 */
    private String defaultSchemaVersion = "execution-plan/1.0";

    /** 允许兼容放行的工具列表。 */
    private List<String> compatibilityTools = new ArrayList<>();

    /** 兼容模式下允许缺失的输出字段，key 为 capabilityName。 */
    private Map<String, List<String>> optionalOutputFieldsByTool = new HashMap<>();

    /** 全局默认最大重试次数。 */
    private Integer globalMaxRetries = 2;

    /** 工具级默认最大重试次数，key 为 capabilityName。 */
    private Map<String, Integer> toolDefaultMaxRetries = new HashMap<>();

    /** 工具级默认 fallback 列表，key 为 capabilityName。 */
    private Map<String, List<String>> toolDefaultFallbacks = new HashMap<>();

    /** 全局默认 fallback 列表。 */
    private List<String> globalFallbackTools = new ArrayList<>();

    /** 是否启用质量门标记。 */
    private boolean qualityGateEnabled = true;
}
