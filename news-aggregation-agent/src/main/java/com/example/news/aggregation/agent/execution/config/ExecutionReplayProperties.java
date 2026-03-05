package com.example.news.aggregation.agent.execution.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Week6 回放 API 配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.agent.replay")
public class ExecutionReplayProperties {

    /** 是否启用回放 API。关闭后统一返回 404。 */
    private boolean apiEnabled = true;

    /** 回放接口默认返回的事件窗口大小（last N）。 */
    private int defaultEventLimit = 100;

    /** 事件分页接口允许的最大 limit，防止超大响应。 */
    private int maxEventLimit = 500;

    /** 是否允许返回原始 payload（仅内部使用）。 */
    private boolean rawPayloadEnabled = false;

    /** 脱敏后文本的最大保留长度。 */
    private int payloadMaskMaxLength = 256;

    /** 默认租户ID，未显式传入租户时用于兼容老请求。 */
    private String defaultTenantId = "default";
}
