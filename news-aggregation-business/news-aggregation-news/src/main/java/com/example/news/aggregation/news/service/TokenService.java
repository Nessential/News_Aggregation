package com.example.news.aggregation.news.service;

import cn.hutool.crypto.SecureUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private static final String TOKEN_PREFIX = "token:login:";
    private static final String TOKEN_AES_KEY = "tokenbynfturbo_0";
    private static final String BEARER_PREFIX = "Bearer ";

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${app.token.expire-hours:24}")
    private long tokenExpireHours;

    public String generateToken(Long userId) {
        String tokenKey = TOKEN_PREFIX + userId;
        String uuid = UUID.randomUUID().toString();
        String tokenValue = tokenKey + ":" + uuid;

        String encryptedToken = SecureUtil.aes(TOKEN_AES_KEY.getBytes(StandardCharsets.UTF_8))
                .encryptBase64(tokenValue);

        redisTemplate.opsForValue().set(tokenKey, uuid, tokenExpireHours, TimeUnit.HOURS);
        log.info("[token] generate token|userId={}|expireHours={}", userId, tokenExpireHours);
        return encryptedToken;
    }

    public Long validateToken(String token) {
        String normalizedToken = normalizeToken(token);
        if (normalizedToken == null) {
            return null;
        }

        try {
            String decryptedToken = SecureUtil.aes(TOKEN_AES_KEY.getBytes(StandardCharsets.UTF_8))
                    .decryptStr(normalizedToken);
            int splitIndex = decryptedToken.lastIndexOf(":");
            if (splitIndex <= 0 || splitIndex >= decryptedToken.length() - 1) {
                return null;
            }
            String tokenKey = decryptedToken.substring(0, splitIndex);
            String uuid = decryptedToken.substring(splitIndex + 1);

            String storedUuid = (String) redisTemplate.opsForValue().get(tokenKey);
            if (storedUuid != null && storedUuid.equals(uuid)) {
                String userIdStr = tokenKey.replace(TOKEN_PREFIX, "");
                return Long.parseLong(userIdStr);
            }
        } catch (Exception e) {
            log.warn("[token] validate failed|error={}", e.getMessage());
        }

        return null;
    }

    public void removeToken(String token) {
        String normalizedToken = normalizeToken(token);
        if (normalizedToken == null) {
            return;
        }

        try {
            String decryptedToken = SecureUtil.aes(TOKEN_AES_KEY.getBytes(StandardCharsets.UTF_8))
                    .decryptStr(normalizedToken);
            int splitIndex = decryptedToken.lastIndexOf(":");
            if (splitIndex <= 0) {
                return;
            }
            String tokenKey = decryptedToken.substring(0, splitIndex);
            redisTemplate.delete(tokenKey);
            log.info("[token] removed|tokenKey={}", tokenKey);
        } catch (Exception e) {
            log.warn("[token] remove failed|error={}", e.getMessage());
        }
    }

    private String normalizeToken(String token) {
        if (token == null) {
            return null;
        }
        String trimmed = token.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String rawToken = trimmed.substring(BEARER_PREFIX.length()).trim();
            return rawToken.isEmpty() ? null : rawToken;
        }
        return trimmed;
    }
}
