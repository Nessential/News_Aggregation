package com.example.news.aggregation.llm.springai.intent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 意图规则配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.llm.intent")
public class IntentRuleProperties {

    /**
     * 意图规则文件路径（支持 classpath: 或文件路径）
     */
    private String rulesPath = "classpath:intent/intent-rules.yml";
}
