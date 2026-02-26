package com.example.news.aggregation.llm.springai.prompt;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 提示词仓库配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.llm.prompts")
public class PromptProperties {

    /**
     * 提示词文件基础路径（支持 classpath: 或文件路径）
     */
    private String basePath = "classpath:prompts/";

    /**
     * 提示词文件后缀
     */
    private String suffix = ".txt";
}
