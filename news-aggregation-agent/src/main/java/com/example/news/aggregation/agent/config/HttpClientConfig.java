package com.example.news.aggregation.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP 客户端配置
 */
@Configuration
public class HttpClientConfig {

    /**
     * RestTemplate Bean（用于调用 LLM/检索服务）
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
