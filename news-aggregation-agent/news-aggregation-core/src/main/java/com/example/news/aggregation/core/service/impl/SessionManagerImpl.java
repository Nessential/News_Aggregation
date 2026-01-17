package com.example.news.aggregation.core.service.impl;

import com.example.news.aggregation.base.exception.BizException;
import com.example.news.aggregation.base.exception.SystemException;
import com.example.news.aggregation.core.domain.FSMState;
import com.example.news.aggregation.core.domain.Message;
import com.example.news.aggregation.core.domain.SessionState;
import com.example.news.aggregation.core.exception.AgentErrorCode;
import com.example.news.aggregation.core.service.SessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * SessionManager实现
 *
 * 使用 Redisson分布式锁 + Redis乐观锁 保证并发安全
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionManagerImpl implements SessionManager {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;

    private static final String SESSION_KEY_PREFIX = "agent:session:";
    private static final String LOCK_KEY_PREFIX = "agent:lock:session:";
    private static final long SESSION_TTL_HOURS = 24;

    @Override
    public SessionState createSession(String userId) {
        String sessionId = UUID.randomUUID().toString();
        SessionState sessionState = SessionState.builder()
                .sessionId(sessionId)
                .userId(userId)
                .currentState(FSMState.INIT)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .version(0L)
                .build();

        String key = SESSION_KEY_PREFIX + sessionId;
        redisTemplate.opsForValue().set(key, sessionState, SESSION_TTL_HOURS, TimeUnit.HOURS);

        log.info("Created session: {}", sessionId);
        return sessionState;
    }

    @Override
    public SessionState getSession(String sessionId) {
        String key = SESSION_KEY_PREFIX + sessionId;
        SessionState sessionState = (SessionState) redisTemplate.opsForValue().get(key);

        if (sessionState == null) {
            throw new BizException(AgentErrorCode.SESSION_NOT_FOUND);
        }

        return sessionState;
    }

    @Override
    public void updateSession(SessionState sessionState) {
        String sessionId = sessionState.getSessionId();
        String lockKey = LOCK_KEY_PREFIX + sessionId;

        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 尝试获取分布式锁（最多等待5秒，锁持有10秒）
            boolean acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BizException(AgentErrorCode.SESSION_LOCK_FAILED);
            }

            // 获取当前版本
            SessionState current = getSession(sessionId);
            if (!current.getVersion().equals(sessionState.getVersion())) {
                throw new BizException(AgentErrorCode.CONCURRENT_ACCESS_CONFLICT);
            }

            // 更新版本号和时间
            sessionState.setVersion(sessionState.getVersion() + 1);
            sessionState.setUpdatedAt(LocalDateTime.now());

            // 保存到Redis
            String key = SESSION_KEY_PREFIX + sessionId;
            redisTemplate.opsForValue().set(key, sessionState, SESSION_TTL_HOURS, TimeUnit.HOURS);

            log.debug("Updated session: {} to version: {}", sessionId, sessionState.getVersion());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SystemException("Failed to acquire lock for session: " + sessionId, e, AgentErrorCode.SESSION_LOCK_FAILED);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void appendMessage(String sessionId, Message message) {
        SessionState sessionState = getSession(sessionId);
        sessionState.getHistory().add(message);
        updateSession(sessionState);
    }

    @Override
    public void updateFSMState(String sessionId, FSMState newState) {
        SessionState sessionState = getSession(sessionId);
        sessionState.setCurrentState(newState);
        updateSession(sessionState);
    }

    @Override
    public List<Message> getHistory(String sessionId) {
        SessionState sessionState = getSession(sessionId);
        return sessionState.getHistory();
    }

    @Override
    public void deleteSession(String sessionId) {
        String key = SESSION_KEY_PREFIX + sessionId;
        redisTemplate.delete(key);
        log.info("Deleted session: {}", sessionId);
    }

    @Override
    public boolean exists(String sessionId) {
        String key = SESSION_KEY_PREFIX + sessionId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}