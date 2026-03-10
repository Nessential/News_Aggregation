package com.example.news.aggregation.agent.service;

import com.example.news.aggregation.agent.domain.Constraints;
import com.example.news.aggregation.agent.domain.RetrievalAttempt;
import com.example.news.aggregation.agent.domain.SessionState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 会话管理器。
 * <p>
 * 负责 Session 级数据读写，以及同会话并发锁控制。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionManager {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;

    @Value("${app.agent.session.timeout-minutes:30}")
    private int sessionTimeoutMinutes;

    @Value("${app.agent.session.max-history-size:50}")
    private int maxHistorySize;

    @Value("${app.agent.session.lock-owner-ttl-seconds:300}")
    private int lockOwnerTtlSeconds;

    private static final String SESSION_KEY_PREFIX = "session:";
    private static final String SESSION_LOCK_KEY_PREFIX = "session:lock:";
    private static final String SESSION_LOCK_OWNER_KEY_PREFIX = "session:lock:owner:";

    /**
     * 创建新会话。
     */
    public SessionState createSession(String userId) {
        String sessionId = generateSessionId();
        LocalDateTime now = LocalDateTime.now();

        SessionState sessionState = SessionState.builder()
                .sessionId(sessionId)
                .userId(userId)
                .budget(10)
                .createdAt(now)
                .updatedAt(now)
                .build();

        saveSession(sessionState);
        log.info("[session] 创建会话成功: sessionId={}, userId={}", sessionId, userId);
        return sessionState;
    }

    /**
     * 使用指定会话ID恢复会话，适用于 Redis 过期后的历史会话续聊。
     */
    public SessionState restoreSession(String sessionId, String userId, List<String> history) {
        LocalDateTime now = LocalDateTime.now();
        List<String> safeHistory = history == null ? new ArrayList<>() : new ArrayList<>(history);
        if (safeHistory.size() > maxHistorySize) {
            safeHistory = new ArrayList<>(safeHistory.subList(safeHistory.size() - maxHistorySize, safeHistory.size()));
        }

        SessionState sessionState = SessionState.builder()
                .sessionId(sessionId)
                .userId(userId)
                .budget(10)
                .history(safeHistory)
                .createdAt(now)
                .updatedAt(now)
                .build();

        saveSession(sessionState);
        log.info("[session] 会话恢复成功: sessionId={}, userId={}, historySize={}", sessionId, userId, safeHistory.size());
        return sessionState;
    }

    /**
     * 获取会话。
     */
    public SessionState getSession(String sessionId) {
        String key = buildKey(sessionId);
        SessionState sessionState = (SessionState) redisTemplate.opsForValue().get(key);
        if (sessionState == null) {
            log.warn("[session] 会话不存在: sessionId={}", sessionId);
            return null;
        }
        // 读会话时刷新过期时间，保持活跃会话可用。
        redisTemplate.expire(key, sessionTimeoutMinutes, TimeUnit.MINUTES);
        return sessionState;
    }

    /**
     * 更新约束条件。
     */
    public void updateConstraints(String sessionId, Constraints constraints) {
        SessionState sessionState = getSession(sessionId);
        if (sessionState == null) {
            log.error("[session] 更新约束失败，会话不存在: sessionId={}", sessionId);
            return;
        }
        sessionState.setConstraints(constraints);
        sessionState.setUpdatedAt(LocalDateTime.now());
        saveSession(sessionState);
    }

    /**
     * 添加历史消息。
     */
    public void addHistory(String sessionId, String message) {
        SessionState sessionState = getSession(sessionId);
        if (sessionState == null) {
            log.error("[session] 写历史失败，会话不存在: sessionId={}", sessionId);
            return;
        }

        sessionState.addHistory(message);
        // 控制历史长度，避免单会话无限增长。
        if (sessionState.getHistory().size() > maxHistorySize) {
            sessionState.setHistory(
                    sessionState.getHistory().subList(
                            sessionState.getHistory().size() - maxHistorySize,
                            sessionState.getHistory().size()
                    )
            );
        }
        saveSession(sessionState);
    }

    /**
     * 记录检索尝试。
     */
    public void addAttempt(String sessionId, RetrievalAttempt attempt) {
        SessionState sessionState = getSession(sessionId);
        if (sessionState == null) {
            log.error("[session] 记录尝试失败，会话不存在: sessionId={}", sessionId);
            return;
        }
        sessionState.addAttempt(attempt);
        saveSession(sessionState);
    }

    /**
     * 更新候选结果。
     */
    public void updateCandidates(String sessionId, List<Long> articleIds) {
        SessionState sessionState = getSession(sessionId);
        if (sessionState == null) {
            log.error("[session] 更新候选失败，会话不存在: sessionId={}", sessionId);
            return;
        }
        sessionState.updateCandidates(articleIds);
        saveSession(sessionState);
    }

    /**
     * 消耗预算。
     */
    public void consumeBudget(String sessionId, int amount) {
        SessionState sessionState = getSession(sessionId);
        if (sessionState == null) {
            log.error("[session] 消耗预算失败，会话不存在: sessionId={}", sessionId);
            return;
        }
        sessionState.consumeBudget(amount);
        saveSession(sessionState);
    }

    /**
     * 删除会话及锁相关状态。
     */
    public void deleteSession(String sessionId) {
        redisTemplate.delete(buildKey(sessionId));
        redisTemplate.delete(buildLockOwnerKey(sessionId));
        try {
            RLock lock = redissonClient.getLock(buildLockKey(sessionId));
            if (lock.isLocked() && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("[session-lock] 删除会话时释放锁失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
        log.info("[session] 删除会话: sessionId={}", sessionId);
    }

    /**
     * 判断会话是否存在。
     */
    public boolean sessionExists(String sessionId) {
        Boolean exists = redisTemplate.hasKey(buildKey(sessionId));
        return exists != null && exists;
    }

    /**
     * 按用户查询最近会话列表，按 updatedAt 倒序返回。
     */
    public List<SessionState> listUserSessions(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            return Collections.emptyList();
        }
        int safeLimit = Math.max(1, limit);
        Set<String> allKeys = redisTemplate.keys(SESSION_KEY_PREFIX + "*");
        if (allKeys == null || allKeys.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> sessionKeys = allKeys.stream()
                .filter(this::isPureSessionKey)
                .toList();
        if (sessionKeys.isEmpty()) {
            return Collections.emptyList();
        }
        List<Object> values = redisTemplate.opsForValue().multiGet(sessionKeys);
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }

        List<SessionState> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof SessionState sessionState)) {
                continue;
            }
            if (!userId.equals(sessionState.getUserId())) {
                continue;
            }
            result.add(sessionState);
        }

        result.sort(Comparator.comparing(this::getSessionSortTime).reversed());
        if (result.size() <= safeLimit) {
            return result;
        }
        return new ArrayList<>(result.subList(0, safeLimit));
    }

    /**
     * 尝试获取会话级锁（Redisson 看门狗自动续租）。
     */
    public boolean tryAcquireSessionLock(String sessionId, String turnId) {
        RLock lock = redissonClient.getLock(buildLockKey(sessionId));
        boolean success = lock.tryLock();
        if (success) {
            setActiveTurnId(sessionId, turnId);
            // 记录锁持有者，便于返回 runningTurnId。TTL 防止异常退出后长期脏数据。
            redisTemplate.opsForValue().set(
                    buildLockOwnerKey(sessionId),
                    turnId,
                    lockOwnerTtlSeconds,
                    TimeUnit.SECONDS
            );
            log.info("[session-lock-step-01] 获取会话锁成功: sessionId={}, turnId={}", sessionId, turnId);
        } else {
            Object holder = redisTemplate.opsForValue().get(buildLockOwnerKey(sessionId));
            log.warn("[session-lock-step-01] 获取会话锁失败: sessionId={}, requestTurnId={}, holderTurnId={}",
                    sessionId, turnId, holder);
        }
        return success;
    }

    /**
     * 释放会话级锁（仅持有线程可释放）。
     */
    public void releaseSessionLock(String sessionId, String turnId) {
        RLock lock = redissonClient.getLock(buildLockKey(sessionId));
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                clearActiveTurnId(sessionId, turnId);
                Object holder = redisTemplate.opsForValue().get(buildLockOwnerKey(sessionId));
                if (holder != null && turnId.equals(String.valueOf(holder))) {
                    redisTemplate.delete(buildLockOwnerKey(sessionId));
                }
                log.info("[session-lock-step-02] 释放会话锁成功: sessionId={}, turnId={}", sessionId, turnId);
                return;
            }
            log.warn("[session-lock-step-02] 当前线程非锁持有者，跳过释放: sessionId={}, turnId={}",
                    sessionId, turnId);
        } catch (Exception e) {
            log.error("[session-lock-step-02] 释放会话锁异常: sessionId={}, turnId={}", sessionId, turnId, e);
        }
    }

    /**
     * 查询当前运行中的 turnId。
     */
    public String getRunningTurnId(String sessionId) {
        Object holder = redisTemplate.opsForValue().get(buildLockOwnerKey(sessionId));
        if (holder != null) {
            return String.valueOf(holder);
        }
        SessionState sessionState = getSession(sessionId);
        return sessionState != null ? sessionState.getActiveTurnId() : null;
    }

    private void saveSession(SessionState sessionState) {
        redisTemplate.opsForValue().set(
                buildKey(sessionState.getSessionId()),
                sessionState,
                sessionTimeoutMinutes,
                TimeUnit.MINUTES
        );
    }

    private String generateSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String buildKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    private String buildLockKey(String sessionId) {
        return SESSION_LOCK_KEY_PREFIX + sessionId;
    }

    private String buildLockOwnerKey(String sessionId) {
        return SESSION_LOCK_OWNER_KEY_PREFIX + sessionId;
    }

    private boolean isPureSessionKey(String key) {
        if (key == null || !key.startsWith(SESSION_KEY_PREFIX)) {
            return false;
        }
        if (key.startsWith(SESSION_LOCK_KEY_PREFIX)) {
            return false;
        }
        if (key.startsWith(SESSION_LOCK_OWNER_KEY_PREFIX)) {
            return false;
        }
        return key.length() > SESSION_KEY_PREFIX.length();
    }

    private LocalDateTime getSessionSortTime(SessionState sessionState) {
        if (sessionState.getUpdatedAt() != null) {
            return sessionState.getUpdatedAt();
        }
        if (sessionState.getCreatedAt() != null) {
            return sessionState.getCreatedAt();
        }
        return LocalDateTime.MIN;
    }

    private void setActiveTurnId(String sessionId, String turnId) {
        SessionState sessionState = getSession(sessionId);
        if (sessionState == null) {
            return;
        }
        sessionState.setActiveTurnId(turnId);
        sessionState.setUpdatedAt(LocalDateTime.now());
        saveSession(sessionState);
    }

    private void clearActiveTurnId(String sessionId, String turnId) {
        SessionState sessionState = getSession(sessionId);
        if (sessionState == null) {
            return;
        }
        if (turnId.equals(sessionState.getActiveTurnId())) {
            sessionState.setActiveTurnId(null);
            sessionState.setUpdatedAt(LocalDateTime.now());
            saveSession(sessionState);
        }
    }
}
