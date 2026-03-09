package com.example.news.aggregation.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关鉴权配置。
 * 对应 application.yml 中 app.auth 下的配置项。
 */
@ConfigurationProperties(prefix = "app.auth")
public class GatewayAuthProperties {

    private boolean enabled = true;
    private String userIdHeader = "X-User-Id";
    private List<String> skipPaths = new ArrayList<>();
    private final Token token = new Token();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUserIdHeader() {
        return userIdHeader;
    }

    public void setUserIdHeader(String userIdHeader) {
        this.userIdHeader = userIdHeader;
    }

    public List<String> getSkipPaths() {
        return skipPaths;
    }

    public void setSkipPaths(List<String> skipPaths) {
        this.skipPaths = skipPaths;
    }

    public Token getToken() {
        return token;
    }

    /**
     * Token 解析与 Redis 校验相关配置。
     */
    public static class Token {
        private String aesKey = "tokenbynfturbo_0";
        private String redisPrefix = "token:login:";
        private long ttlSeconds = 86400;

        public String getAesKey() {
            return aesKey;
        }

        public void setAesKey(String aesKey) {
            this.aesKey = aesKey;
        }

        public String getRedisPrefix() {
            return redisPrefix;
        }

        public void setRedisPrefix(String redisPrefix) {
            this.redisPrefix = redisPrefix;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }
}
