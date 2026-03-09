package com.example.news.aggregation.gateway.security;

/**
 * 解析后的 Token 结构：
 * tokenKey: Redis 键（如 token:login:123）
 * uuid: Redis 值
 * userId: 当前登录用户ID
 */
public record TokenPayload(String tokenKey, String uuid, Long userId) {
}
