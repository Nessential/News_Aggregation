package com.example.news.aggregation.llm.springai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;

/**
 * Spring AI LLM Application
 */
@SpringBootApplication(scanBasePackages = "com.example.news.aggregation")
@EnableDubbo
public class SpringAiApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(SpringAiApplication.class, args);
    }
}
