package com.example.news.aggregation.agent.workflow.validation;

/**
 * Schema 校验模式。
 * STRICT: 严格拦截；COMPAT: 兼容放行并打告警。
 */
public enum SchemaValidationMode {
    STRICT,
    COMPAT
}

