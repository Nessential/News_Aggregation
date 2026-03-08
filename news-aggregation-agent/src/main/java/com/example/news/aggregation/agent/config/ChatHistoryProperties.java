package com.example.news.aggregation.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对话历史配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.agent.chat-history")
public class ChatHistoryProperties {

    /** 是否启用对话历史记录。 */
    private boolean enabled = true;

    /** 最多保留对话轮次。 */
    private int maxKeepTurns = 50;

    /** LLM 上下文使用最近N轮。 */
    private int llmPromptTurns = 5;

    /** 历史保留天数。 */
    private int retentionDays = 90;

    /** 清理任务 Cron 表达式（默认每天凌晨3点）。 */
    private String cleanupCron = "0 0 3 * * ?";
}
