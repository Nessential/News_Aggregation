package com.example.news.aggregation.llm.springai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring AI LLM Application
 */
@SpringBootApplication(scanBasePackages = "com.example.news.aggregation")
public class SpringAiApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(SpringAiApplication.class, args);
    }
}
