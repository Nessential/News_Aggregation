package com.example.news.aggregation.llm.springai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 配置类
 * 配置LLM调用所需的ChatClient
 */
@Configuration
public class AiConfig {
    
    /**
     * 配置ChatClient.Builder Bean
     * Spring Boot会自动配置DashScope ChatModel
     */
    @Bean
    public ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}
