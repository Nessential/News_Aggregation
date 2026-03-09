package com.example.news.aggregation.web.util;

import cn.hutool.crypto.SecureUtil;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.example.news.aggregation.cache.constant.CacheConstant.CACHE_KEY_SEPARATOR;

public class TokenUtil {

    private static final String TOEKN_AES_KEY = "tokenbynfturbo_0";
    private static final Pattern USER_TOKEN_KEY_PATTERN = Pattern.compile("^token:login:\\d+$");

    public static final String TOKEN_PREFIX = "token:";

    private static final String BEARER_PREFIX = "Bearer ";

    public static String getTokenValueByKey(String tokenKey) {
        if (tokenKey == null) {
            return null;
        }
        String uuid = UUID.randomUUID().toString();
        //token:buy:29:10085:5ac6542b-64b1-4d41-91b9-e6c55849bb7f
        String tokenValue = tokenKey + CACHE_KEY_SEPARATOR + uuid;

        //YZdkYfQ8fy7biSTsS5oZrbsB8eN7dHPgtCV0dw/36AHSfDQzWOj+ULNEcMluHvep/txjP+BqVRH3JlprS8tWrQ==
        return SecureUtil.aes(TOEKN_AES_KEY.getBytes(StandardCharsets.UTF_8)).encryptBase64(tokenValue);
    }

    public static String getTokenKeyByValue(String tokenValue) {
        TokenPayload payload = parseToken(tokenValue);
        return payload == null ? null : payload.tokenKey();
    }

    public static TokenPayload parseToken(String tokenValue) {
        if (tokenValue == null) {
            return null;
        }
        String normalized = normalizeToken(tokenValue);
        if (normalized == null) {
            return null;
        }
        String decryptTokenValue = SecureUtil.aes(TOEKN_AES_KEY.getBytes(StandardCharsets.UTF_8)).decryptStr(normalized);
        int splitIndex = decryptTokenValue.lastIndexOf(CACHE_KEY_SEPARATOR);
        if (splitIndex <= 0 || splitIndex >= decryptTokenValue.length() - 1) {
            return null;
        }
        String tokenKey = decryptTokenValue.substring(0, splitIndex);
        String uuid = decryptTokenValue.substring(splitIndex + 1);

        Long userId = null;
        if (USER_TOKEN_KEY_PATTERN.matcher(tokenKey).matches()) {
            String userIdPart = tokenKey.substring("token:login:".length());
            userId = Long.parseLong(userIdPart);
        }
        return new TokenPayload(tokenKey, uuid, userId);
    }

    public static String normalizeToken(String tokenValue) {
        if (tokenValue == null) {
            return null;
        }
        String trimmed = tokenValue.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String raw = trimmed.substring(BEARER_PREFIX.length()).trim();
            return raw.isEmpty() ? null : raw;
        }
        return trimmed;
    }

    public record TokenPayload(String tokenKey, String uuid, Long userId) {
    }
}
