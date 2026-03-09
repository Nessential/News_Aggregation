package com.example.news.aggregation.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;

@EnableDubbo
@SpringBootApplication(scanBasePackages = {
        "com.example.news.aggregation"
})
public class NewAggregationAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(NewAggregationAppApplication.class, args);
    }

}
