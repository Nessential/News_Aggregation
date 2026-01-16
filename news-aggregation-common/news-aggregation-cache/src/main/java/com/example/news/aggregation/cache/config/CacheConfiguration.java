package com.example.news.aggregation.cache.config;

import com.alicp.jetcache.anno.config.EnableCreateCacheAnnotation;
import com.alicp.jetcache.anno.config.EnableMethodCache;
import org.springframework.context.annotation.Configuration;

/**
 * 缓存配置
 *
 * @EnableMethodCache: 启用 @Cached、@CacheUpdate、@CacheInvalidate 注解
 * @EnableCreateCacheAnnotation: 启用 @CreateCache 注解（编程式缓存）
 */
@Configuration
@EnableMethodCache(basePackages = "com.example")  // 扫描所有包
@EnableCreateCacheAnnotation
public class CacheConfiguration {
    // JetCache 配置主要在 application-cache.yml 中
}