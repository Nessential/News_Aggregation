package com.example.news.aggregation.agent.service;

import com.example.news.aggregation.agent.domain.Constraints;
import com.example.news.aggregation.agent.domain.RetrievalAttempt;
import com.example.news.aggregation.agent.domain.SessionState;
import com.example.news.aggregation.agent.enums.ConversationState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 会话管理器。
 * 负责 Session 状态的创建、读取与更新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionManager {

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${app.agent.session.timeout-minutes:30}")
    private int sessionTimeoutMinutes;

    @Value("${app.agent.session.max-history-size:50}")
    private int maxHistorySize;

    private static final String SESSION_KEY_PREFIX = "session:";

    /**
     * 创建新会话。
     */
    public SessionState createSession(String userId) {
        String sessionId = generateSessionId();
        LocalDateTime now = LocalDateTime.now();

        SessionState sessionState = SessionState.builder()
                .sessionId(sessionId)
                .userId(userId)
                .conversationState(ConversationState.START)
                .budget(10)
                .createdAt(now)
                .updatedAt(now)
                .build();

        saveSession(sessionState);
        log.info("Created new session: {} for user: {}", sessionId, userId);
        return sessionState;
    }

    /**
     * 获取会话状态。
     */
    public SessionState getSession(String sessionId) {
        String key = buildKey(sessionId);
        SessionState sessionState = (SessionState) redisTemplate.opsForValue().get(key);
        if (sessionState == null) {
            log.warn("Session not found: {}", sessionId);
            return null;
        }
        // 刷新过期时间
        redisTemplate.expire(key, sessionTimeoutMinutes, TimeUnit.MINUTES);
        return sessionState;
    }

    /**
     * 更新约束条件。
     */
    public void updateConstraints(String sessionId, Constraints constraints) {
        SessionState sessionState = getSession(sessionId);
        if (sessionState == null) {
            log.error("Cannot update constraints for non-existent session: {}", sessionId);
            return;
        }
        sessionState.setConstraints(constraints);
        sessionState.setUpdatedAt(LocalDateTime.now());
        saveSession(sessionState);
        log.debug("Updated constraints for session: {}", sessionId);
    }

    /**
     * 添加对话历史。
     */
    public void addHistory(String sessionId, String message) {
        SessionState sessionState = getSession(sessionId);
        if (sessionState == null) {
            log.error("Cannot add history for non-existent session: {}", sessionId);
            return;
        }
        sessionState.addHistory(message);

        // 限制历史记录大小

        if (sessionState.getHistory().size() > maxHistorySize) {
            sessionState.setHistory(
                    sessionState.getHistory().subList(
                            sessionState.getHistory().size() - maxHistorySize,
                            sessionState.getHistory().size()
                    )
            );
        }
        saveSession(sessionState);
        log.debug("Added history to session: {}, current size: {}", sessionId, sessionState.getHistory().size());
    }

    /**
     * 记录检索尝试。
     */
    public void addAttempt(String sessionId, RetrievalAttempt attempt) {
        SessionState sessionState = getSession(sessionId);
        if (sessionState == null) {
            log.error("Cannot add attempt for non-existent session: {}", sessionId);
            return;
        }
        sessionState.addAttempt(attempt);
        saveSession(sessionState);
        log.debug("Recorded retrieval attempt #{} for session: {}", attempt.getAttemptNumber(), sessionId);
    }

    /**
     * 更新候选结果。
     */
    public void updateCandidates(String sessionId, List<Long> articleIds) {
        SessionState sessionState = getSession(sessionId);
        if (sessionState == null) {
            log.error("Cannot update candidates for non-existent session: {}", sessionId);
            return;
        }

        sessionState.updateCandidates(articleIds);
        saveSession(sessionState);
        log.debug("Updated candidates for session: {}, count: {}", sessionId, articleIds.size());
    }

    /**
     * 更新对话状态。
     */
    public void updateConversationState(String sessionId, ConversationState newState) {
        SessionState sessionState = getSession(sessionId);
        if (sessionState == null) {
            log.error("Cannot update state for non-existent session: {}", sessionId);
            return;
        }

        ConversationState oldState = sessionState.getConversationState();
        sessionState.setConversationState(newState);
        sessionState.setUpdatedAt(LocalDateTime.now());
        saveSession(sessionState);
        log.info("[fsm] Session {} state transition: {} -> {}", sessionId, oldState, newState);
    }

    /**
     * 消耗预算。
     */
    public void consumeBudget(String sessionId, int amount) {
        SessionState sessionState = getSession(sessionId);
        if (sessionState == null) {
            log.error("Cannot consume budget for non-existent session: {}", sessionId);
            return;
        }

        sessionState.consumeBudget(amount);
        saveSession(sessionState);
        log.debug("Consumed {} budget for session: {}, remaining: {}", amount, sessionId, sessionState.getBudget());
    }

    /**
     * 删除会话。
     */
    public void deleteSession(String sessionId) {
        String key = buildKey(sessionId);
        redisTemplate.delete(key);
        log.info("Deleted session: {}", sessionId);
    }

    /**
     * 检查会话是否存在。
     */
    public boolean sessionExists(String sessionId) {
        String key = buildKey(sessionId);
        Boolean exists = redisTemplate.hasKey(key);
        return exists != null && exists;
    }

    // ========= Private Methods =========

    /**
     * 保存会话到 Redis。
     */
    private void saveSession(SessionState sessionState) {
        String key = buildKey(sessionState.getSessionId());
        redisTemplate.opsForValue().set(key, sessionState, sessionTimeoutMinutes, TimeUnit.MINUTES);
    }

    /**
     * 生成会话 ID。
     */
    private String generateSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 构建 Redis Key。
     */
    private String buildKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }
}
