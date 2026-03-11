package com.example.news.aggregation.cache.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置。
 * <p>
 * lockWatchdogTimeout 用于看门狗续租，避免长请求执行时锁提前过期。
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:${spring.redis.host:localhost}}")
    private String host;

    @Value("${spring.data.redis.port:${spring.redis.port:6379}}")
    private Integer port;

    @Value("${spring.data.redis.password:${spring.redis.password:}}")
    private String password;

    @Value("${spring.data.redis.username:${spring.redis.username:}}")
    private String username;

    @Value("${spring.data.redis.database:${spring.redis.database:0}}")
    private Integer database;

    @Value("${app.agent.session.watchdog-timeout-ms:90000}")
    private Long watchdogTimeoutMs;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.setLockWatchdogTimeout(watchdogTimeoutMs);

        String address = "redis://" + host + ":" + port;
        var singleServer = config.useSingleServer()
                .setAddress(address)
                .setPassword(password == null || password.isBlank() ? null : password)
                .setDatabase(database)
                .setConnectionPoolSize(200)
                .setConnectionMinimumIdleSize(10)
                .setRetryAttempts(3)
                .setRetryInterval(1500);

        if (username != null && !username.isBlank()) {
            singleServer.setUsername(username);
        }

        return Redisson.create(config);
    }
}
