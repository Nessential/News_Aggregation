package com.example.news.aggregation.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 网关启动入口。
 * 负责统一鉴权、路由转发、身份头注入。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
