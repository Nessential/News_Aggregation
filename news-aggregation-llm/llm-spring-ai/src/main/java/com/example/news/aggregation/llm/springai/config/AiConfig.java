package com.example.news.aggregation.llm.springai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.example.news.aggregation.llm.springai.prompt.PromptRegistry;

/**
 * Spring AI 配置类
 * 配置LLM调用所需的ChatClient
 */
@Configuration
@EnableConfigurationProperties({
        GraphProperties.class,
        PlannerResourceEstimationProperties.class
})
public class AiConfig {
    
    /**
     * 配置ChatClient.Builder Bean
     * Spring Boot会自动配置DashScope ChatModel
     */
    @Bean
    public ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
    
    /**
     * 配置ChatClient Bean
     * 用于Agent模块的Pipeline调用
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, PromptRegistry promptRegistry) {
        String systemPrompt = promptRegistry.getPrompt("system-default", "");
        ChatClient.Builder configured = builder;
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            configured = configured.defaultSystem(systemPrompt);
        }
        return configured.build();
    }
}
