package com.example.news.aggregation.cache.quota.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Feature quota configuration.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.feature-quota")
public class FeatureQuotaProperties {

    /**
     * Global switch of quota.
     */
    private boolean enabled = true;

    /**
     * Whether to fail-open when quota component throws exception.
     */
    private boolean failOpen = true;

    /**
     * Redis key prefix.
     */
    private String redisPrefix = "quota:daily";

    /**
     * Zone id used to calculate next midnight.
     */
    private String zoneId;

    /**
     * Per-feature quota configs.
     */
    private Map<String, FeatureConfig> features = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class FeatureConfig {

        /**
         * Feature-level switch.
         */
        private boolean enabled = true;

        /**
         * Daily limit count.
         */
        private int dailyLimit = 0;
    }
}
