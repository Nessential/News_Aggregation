package com.example.news.aggregation.gateway.security;

import cn.hutool.crypto.SecureUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Token 校验器。
 * 逻辑：
 * 1. 解析 Authorization（支持 Bearer）
 * 2. 解密 token 得到 tokenKey + uuid + userId
 * 3. 校验 Redis 中 uuid 是否一致
 * 4. 校验通过后刷新 TTL 并返回 userId
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenValidator {

    private static final String BEARER_PREFIX = "Bearer ";

    private final StringRedisTemplate redisTemplate;
    private final GatewayAuthProperties authProperties;

    /**
     * 校验 token 并返回详细结果。
     */
    public ValidationResult validate(String authorizationHeader) {
        String token = normalizeBearer(authorizationHeader);
        if (token == null) {
            return ValidationResult.failed("TOKEN_EMPTY", "token为空");
        }

        TokenPayload payload = parseToken(token);
        if (payload == null || payload.userId() == null) {
            return ValidationResult.failed("TOKEN_PARSE_FAILED", "token解析失败");
        }

        String storedUuidRaw = redisTemplate.opsForValue().get(payload.tokenKey());
        if (storedUuidRaw == null) {
            return ValidationResult.failed("TOKEN_NOT_FOUND", "token不存在或已过期");
        }
        String storedUuid = normalizeStoredUuid(storedUuidRaw);
        if (!storedUuid.equals(payload.uuid())) {
            log.warn("[网关鉴权] token校验失败|tokenKey={} |redisUuid={} |tokenUuid={} |rawRedisValue={}",
                    payload.tokenKey(), storedUuid, payload.uuid(), storedUuidRaw);
            return ValidationResult.failed("TOKEN_MISMATCH", "token校验不通过（uuid不匹配）");
        }

        long ttlSeconds = Math.max(authProperties.getToken().getTtlSeconds(), 1L);
        redisTemplate.expire(payload.tokenKey(), Duration.ofSeconds(ttlSeconds));
        return ValidationResult.ok(payload.userId());
    }

    /**
     * 标准化 Authorization：
     * - 支持 Bearer 前缀
     * - 允许直接传裸 token（兼容旧调用）
     */
    private String normalizeBearer(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        String trimmed = authorizationHeader.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String rawToken = trimmed.substring(BEARER_PREFIX.length()).trim();
            return rawToken.isEmpty() ? null : rawToken;
        }
        return trimmed;
    }

    /**
     * 解密并解析 token。
     */
    private TokenPayload parseToken(String token) {
        try {
            String aesKey = authProperties.getToken().getAesKey();
            String redisPrefix = authProperties.getToken().getRedisPrefix();
            String decrypted = SecureUtil.aes(aesKey.getBytes(StandardCharsets.UTF_8)).decryptStr(token);

            int splitIndex = decrypted.lastIndexOf(':');
            if (splitIndex <= 0 || splitIndex >= decrypted.length() - 1) {
                return null;
            }

            String tokenKey = decrypted.substring(0, splitIndex);
            String uuid = decrypted.substring(splitIndex + 1);
            if (!tokenKey.startsWith(redisPrefix)) {
                return null;
            }

            String userIdRaw = tokenKey.substring(redisPrefix.length());
            Long userId = Long.parseLong(userIdRaw);
            return new TokenPayload(tokenKey, uuid, userId);
        } catch (Exception ex) {
            log.debug("[网关鉴权] Token 解析失败", ex);
            return null;
        }
    }

    /**
     * 兼容 GenericJackson2JsonRedisSerializer 存储的 JSON 字符串。
     * 例如 Redis 值为 "\"abc-uuid\"" 时，归一化为 "abc-uuid"。
     */
    private String normalizeStoredUuid(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 兼容旧调用：仅返回 userId。
     */
    public Long validateAndGetUserId(String authorizationHeader) {
        ValidationResult result = validate(authorizationHeader);
        return result.valid() ? result.userId() : null;
    }

    public record ValidationResult(boolean valid, Long userId, String errorCode, String errorMessage) {
        public static ValidationResult ok(Long userId) {
            return new ValidationResult(true, userId, null, null);
        }

        public static ValidationResult failed(String errorCode, String errorMessage) {
            return new ValidationResult(false, null, errorCode, errorMessage);
        }
    }
}
