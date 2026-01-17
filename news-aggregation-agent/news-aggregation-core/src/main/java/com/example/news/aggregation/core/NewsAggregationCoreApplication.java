package com.example.news.aggregation.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Agent System Main Application
 */
@SpringBootApplication(scanBasePackages = {
        "com.example.news.aggregation"
})
public class NewsAggregationCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(NewsAggregationCoreApplication.class, args);
    }

}
