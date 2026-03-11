package com.example.news.aggregation.cache.quota.service.impl;

import com.example.news.aggregation.cache.quota.config.FeatureQuotaProperties;
import com.example.news.aggregation.cache.quota.model.FeatureQuotaCheckResult;
import com.example.news.aggregation.cache.quota.model.FeatureQuotaConsumeResult;
import com.example.news.aggregation.cache.quota.model.FeatureQuotaSnapshot;
import com.example.news.aggregation.cache.quota.service.FeatureQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed feature quota service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureQuotaServiceImpl implements FeatureQuotaService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final FeatureQuotaProperties properties;

    @Override
    public FeatureQuotaCheckResult checkQuota(Long userId, String featureCode) {
        if (!properties.isEnabled()) {
            return FeatureQuotaCheckResult.builder()
                    .allowed(true)
                    .reasonCode("QUOTA_DISABLED")
                    .allQuotas(queryAllQuotasSafe(userId))
                    .build();
        }
        try {
            String normalizedFeature = normalizeFeature(featureCode);
            FeatureQuotaProperties.FeatureConfig featureConfig = properties.getFeatures().get(normalizedFeature);
            if (featureConfig == null || !featureConfig.isEnabled() || featureConfig.getDailyLimit() <= 0) {
                return FeatureQuotaCheckResult.builder()
                        .allowed(true)
                        .reasonCode("FEATURE_QUOTA_DISABLED")
                        .allQuotas(queryAllQuotasSafe(userId))
                        .build();
            }

            FeatureQuotaSnapshot snapshot = getSnapshot(userId, normalizedFeature, featureConfig);
            if (snapshot.getRemainingCount() <= 0) {
                return FeatureQuotaCheckResult.builder()
                        .allowed(false)
                        .reasonCode("FEATURE_QUOTA_EXCEEDED")
                        .reasonMessage("\u4eca\u65e5\u989d\u5ea6\u5df2\u7528\u5c3d")
                        .allQuotas(queryAllQuotasSafe(userId))
                        .build();
            }

            return FeatureQuotaCheckResult.builder()
                    .allowed(true)
                    .reasonCode("OK")
                    .allQuotas(queryAllQuotasSafe(userId))
                    .build();
        } catch (Exception ex) {
            if (properties.isFailOpen()) {
                log.error("[quota] \u8bf7\u6c42\u524d\u68c0\u67e5\u5f02\u5e38\uff0c\u6309 fail-open \u653e\u884c|userId={} |feature={}", userId, featureCode, ex);
                return FeatureQuotaCheckResult.builder()
                        .allowed(true)
                        .reasonCode("QUOTA_CHECK_ERROR_FAIL_OPEN")
                        .allQuotas(Collections.emptyMap())
                        .build();
            }
            throw ex;
        }
    }

    @Override
    public FeatureQuotaConsumeResult consumeQuota(Long userId, String featureCode) {
        if (!properties.isEnabled()) {
            return FeatureQuotaConsumeResult.builder()
                    .consumed(false)
                    .reasonCode("QUOTA_DISABLED")
                    .allQuotas(queryAllQuotasSafe(userId))
                    .build();
        }
        try {
            String normalizedFeature = normalizeFeature(featureCode);
            FeatureQuotaProperties.FeatureConfig featureConfig = properties.getFeatures().get(normalizedFeature);
            if (featureConfig == null || !featureConfig.isEnabled() || featureConfig.getDailyLimit() <= 0) {
                return FeatureQuotaConsumeResult.builder()
                        .consumed(false)
                        .reasonCode("FEATURE_QUOTA_DISABLED")
                        .allQuotas(queryAllQuotasSafe(userId))
                        .build();
            }

            String key = buildDailyHashKey(userId);
            long ttlSeconds = secondsToMidnight();
            ensureHashInitialized(key, normalizedFeature, featureConfig.getDailyLimit(), ttlSeconds);
            String usedField = usedField(normalizedFeature);
            Long after = redisTemplate.opsForHash().increment(key, usedField, 1L);
            ensureExpireIfMissing(key, ttlSeconds);

            if (after == null) {
                return FeatureQuotaConsumeResult.builder()
                        .consumed(false)
                        .reasonCode("QUOTA_CONSUME_NULL")
                        .reasonMessage("\u989d\u5ea6\u6263\u51cf\u8fd4\u56de\u7a7a\u7ed3\u679c")
                        .allQuotas(queryAllQuotasSafe(userId))
                        .build();
            }

            int effectiveLimit = Math.max(featureConfig.getDailyLimit(), 0);
            if (after > effectiveLimit) {
                // Rollback over-consume to keep counter in sync.
                redisTemplate.opsForHash().increment(key, usedField, -1L);
                return FeatureQuotaConsumeResult.builder()
                        .consumed(false)
                        .reasonCode("FEATURE_QUOTA_EXCEEDED")
                        .reasonMessage("\u4eca\u65e5\u989d\u5ea6\u5df2\u7528\u5c3d")
                        .allQuotas(queryAllQuotasSafe(userId))
                        .build();
            }

            return FeatureQuotaConsumeResult.builder()
                    .consumed(true)
                    .reasonCode("OK")
                    .allQuotas(queryAllQuotasSafe(userId))
                    .build();
        } catch (Exception ex) {
            if (properties.isFailOpen()) {
                log.error("[quota] \u6210\u529f\u540e\u6263\u51cf\u5f02\u5e38\uff0c\u6309 fail-open \u5ffd\u7565|userId={} |feature={}", userId, featureCode, ex);
                return FeatureQuotaConsumeResult.builder()
                        .consumed(false)
                        .reasonCode("QUOTA_CONSUME_ERROR_FAIL_OPEN")
                        .allQuotas(Collections.emptyMap())
                        .build();
            }
            throw ex;
        }
    }

    @Override
    public Map<String, FeatureQuotaSnapshot> queryAllQuotas(Long userId) {
        Map<String, FeatureQuotaSnapshot> result = new LinkedHashMap<>();
        if (userId == null || userId <= 0 || properties.getFeatures() == null || properties.getFeatures().isEmpty()) {
            return result;
        }
        for (Map.Entry<String, FeatureQuotaProperties.FeatureConfig> entry : properties.getFeatures().entrySet()) {
            String featureCode = normalizeFeature(entry.getKey());
            FeatureQuotaProperties.FeatureConfig featureConfig = entry.getValue();
            if (featureConfig == null) {
                continue;
            }
            if (!properties.isEnabled() || !featureConfig.isEnabled() || featureConfig.getDailyLimit() <= 0) {
                result.put(featureCode, FeatureQuotaSnapshot.builder()
                        .featureCode(featureCode)
                        .enabled(false)
                        .dailyLimit(featureConfig.getDailyLimit())
                        .usedCount(0)
                        .remainingCount(featureConfig.getDailyLimit())
                        .expireAtEpochMs(endOfTodayEpochMs())
                        .build());
                continue;
            }
            result.put(featureCode, getSnapshot(userId, featureCode, featureConfig));
        }
        return result;
    }

    private Map<String, FeatureQuotaSnapshot> queryAllQuotasSafe(Long userId) {
        try {
            return queryAllQuotas(userId);
        } catch (Exception ex) {
            log.error("[quota] \u67e5\u8be2\u9650\u989d\u5feb\u7167\u5f02\u5e38|userId={}", userId, ex);
            return Collections.emptyMap();
        }
    }

    private FeatureQuotaSnapshot getSnapshot(Long userId, String featureCode, FeatureQuotaProperties.FeatureConfig featureConfig) {
        String key = buildDailyHashKey(userId);
        long ttlSeconds = secondsToMidnight();
        ensureHashInitialized(key, featureCode, featureConfig.getDailyLimit(), ttlSeconds);
        ensureExpireIfMissing(key, ttlSeconds);
        int used = safeToInt(redisTemplate.opsForHash().get(key, usedField(featureCode)));
        int limit = Math.max(featureConfig.getDailyLimit(), 0);
        int remaining = Math.max(limit - used, 0);
        long expireAtEpochMs = System.currentTimeMillis() + Math.max(getTtlSeconds(key), 0) * 1000;
        return FeatureQuotaSnapshot.builder()
                .featureCode(featureCode)
                .enabled(true)
                .dailyLimit(limit)
                .usedCount(used)
                .remainingCount(remaining)
                .expireAtEpochMs(expireAtEpochMs)
                .build();
    }

    private void ensureHashInitialized(String key, String featureCode, int defaultLimit, long ttlSeconds) {
        redisTemplate.opsForHash().putIfAbsent(key, usedField(featureCode), 0);
        ensureExpireIfMissing(key, ttlSeconds);
    }

    private String usedField(String featureCode) {
        return featureCode + ":used";
    }

    private void ensureExpireIfMissing(String key, long ttlSeconds) {
        Long currentTtl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (currentTtl == null || currentTtl <= 0) {
            redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
        }
    }

    private long getTtlSeconds(String key) {
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttl == null || ttl < 0) {
            return secondsToMidnight();
        }
        return ttl;
    }

    private int safeToInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String str = String.valueOf(value).trim();
        if (str.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(str);
        } catch (Exception ignore) {
            return 0;
        }
    }

    private String buildDailyHashKey(Long userId) {
        return properties.getRedisPrefix() + ":" + userId + ":" + LocalDate.now(resolveZoneId());
    }

    private String normalizeFeature(String featureCode) {
        return featureCode == null ? "" : featureCode.trim().toLowerCase(Locale.ROOT);
    }

    private ZoneId resolveZoneId() {
        if (properties.getZoneId() == null || properties.getZoneId().isBlank()) {
            return ZoneId.systemDefault();
        }
        return ZoneId.of(properties.getZoneId());
    }

    private long secondsToMidnight() {
        ZoneId zoneId = resolveZoneId();
        LocalDateTime now = LocalDateTime.now(zoneId);
        LocalDateTime tomorrowStart = now.toLocalDate().plusDays(1).atStartOfDay();
        long seconds = Duration.between(now, tomorrowStart).getSeconds();
        return Math.max(seconds, 1);
    }

    private long endOfTodayEpochMs() {
        ZoneId zoneId = resolveZoneId();
        LocalDateTime tomorrowStart = LocalDate.now(zoneId).plusDays(1).atStartOfDay();
        return tomorrowStart.atZone(zoneId).toInstant().toEpochMilli();
    }
}
