package com.example.news.aggregation.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Agent 启动类。
 */
@EnableScheduling
@SpringBootApplication(scanBasePackages = {
        "com.example.news.aggregation.agent",
        "com.example.news.aggregation.vector",
        "com.example.news.aggregation.es",
        "com.example.news.aggregation.embedding",
        "com.example.news.aggregation.cache",
        "com.example.news.aggregation.base",
        "com.example.news.aggregation.datasource"
        // 不扫描 job 包，避免加载 XxlJobConfig
})
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
