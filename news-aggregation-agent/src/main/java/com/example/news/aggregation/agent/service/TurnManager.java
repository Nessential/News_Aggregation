package com.example.news.aggregation.agent.service;

import com.example.news.aggregation.agent.domain.AgentResponse;
import com.example.news.aggregation.agent.domain.IdempotencyRecord;
import com.example.news.aggregation.agent.domain.TurnState;
import com.example.news.aggregation.agent.enums.ConversationState;
import com.example.news.aggregation.agent.enums.IdempotencyStatus;
import com.example.news.aggregation.agent.enums.TurnStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Turn 生命周期管理器。
 * <p>
 * 职责：
 * 1) 持久化单轮执行状态；
 * 2) 维护幂等记录（IN_PROGRESS/DONE/FAILED）；
 * 3) 提供结果快照回放能力。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TurnManager {

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${app.agent.session.timeout-minutes:30}")
    private int sessionTimeoutMinutes;

    @Value("${app.agent.turn.key-prefix:turn:}")
    private String turnKeyPrefix;

    @Value("${app.agent.turn.idempotent-prefix:idem:}")
    private String idempotentKeyPrefix;

    /**
     * 创建并持久化 RUNNING 状态的 turn。
     */
    public TurnState createRunningTurn(String sessionId, String turnId, String requestHash) {
        LocalDateTime now = LocalDateTime.now();
        TurnState state = TurnState.builder()
                .sessionId(sessionId)
                .turnId(turnId)
                .requestHash(requestHash)
                .status(TurnStatus.RUNNING)
                .fsmState(ConversationState.START)
                .createdAt(now)
                .updatedAt(now)
                .build();
        saveTurnState(state);
        log.info("[turn] 创建运行中轮次: sessionId={}, turnId={}", sessionId, turnId);
        return state;
    }

    /**
     * 更新 turn 内 FSM 状态，便于定位状态紊乱问题。
     */
    public void updateFsmState(String sessionId, String turnId, ConversationState fsmState) {
        TurnState current = getTurnState(sessionId, turnId);
        if (current == null) {
            return;
        }
        current.setFsmState(fsmState);
        current.setUpdatedAt(LocalDateTime.now());
        saveTurnState(current);
    }

    /**
     * 标记 turn 成功完成并写入结果快照。
     */
    public void markTurnDone(String sessionId, String turnId, AgentResponse response) {
        TurnState current = getTurnState(sessionId, turnId);
        if (current == null) {
            current = createRunningTurn(sessionId, turnId, "");
        }
        LocalDateTime now = LocalDateTime.now();
        current.setStatus(TurnStatus.DONE);
        current.setResultSnapshot(response);
        current.setUpdatedAt(now);
        current.setFinishedAt(now);
        saveTurnState(current);
        log.info("[turn] 轮次执行完成: sessionId={}, turnId={}", sessionId, turnId);
    }

    /**
     * 标记 turn 失败并保存错误信息。
     */
    public void markTurnFailed(String sessionId, String turnId, String errorCode, String errorMessage) {
        TurnState current = getTurnState(sessionId, turnId);
        if (current == null) {
            current = createRunningTurn(sessionId, turnId, "");
        }
        LocalDateTime now = LocalDateTime.now();
        current.setStatus(TurnStatus.FAILED);
        current.setErrorCode(errorCode);
        current.setErrorMessage(errorMessage);
        current.setUpdatedAt(now);
        current.setFinishedAt(now);
        saveTurnState(current);
        log.warn("[turn] 轮次执行失败: sessionId={}, turnId={}, errorCode={}", sessionId, turnId, errorCode);
    }

    /**
     * 查询 turn 状态。
     */
    public TurnState getTurnState(String sessionId, String turnId) {
        return (TurnState) redisTemplate.opsForValue().get(buildTurnKey(sessionId, turnId));
    }

    /**
     * 幂等记录：查询。
     */
    public IdempotencyRecord getIdempotencyRecord(String sessionId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return (IdempotencyRecord) redisTemplate.opsForValue().get(buildIdempotentKey(sessionId, idempotencyKey));
    }

    /**
     * 幂等记录：尝试创建 IN_PROGRESS（原子 setIfAbsent）。
     */
    public boolean tryCreateInProgressRecord(String sessionId,
                                             String idempotencyKey,
                                             String turnId,
                                             String requestHash) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        IdempotencyRecord record = IdempotencyRecord.builder()
                .idempotencyKey(idempotencyKey)
                .sessionId(sessionId)
                .turnId(turnId)
                .requestHash(requestHash)
                .status(IdempotencyStatus.IN_PROGRESS)
                .createdAt(now)
                .updatedAt(now)
                .build();
        Boolean created = redisTemplate.opsForValue().setIfAbsent(
                buildIdempotentKey(sessionId, idempotencyKey),
                record,
                sessionTimeoutMinutes,
                TimeUnit.MINUTES
        );
        boolean success = Boolean.TRUE.equals(created);
        if (success) {
            log.info("[idem] 创建处理中记录: sessionId={}, idempotencyKey={}, turnId={}",
                    sessionId, idempotencyKey, turnId);
        }
        return success;
    }

    /**
     * 幂等记录：更新为 DONE 并保存响应快照。
     */
    public void markIdempotencyDone(String sessionId,
                                    String idempotencyKey,
                                    String turnId,
                                    AgentResponse response) {
        IdempotencyRecord record = getIdempotencyRecord(sessionId, idempotencyKey);
        LocalDateTime now = LocalDateTime.now();
        if (record == null) {
            record = IdempotencyRecord.builder()
                    .idempotencyKey(idempotencyKey)
                    .sessionId(sessionId)
                    .turnId(turnId)
                    .createdAt(now)
                    .build();
        }
        record.setTurnId(turnId);
        record.setStatus(IdempotencyStatus.DONE);
        record.setResponseSnapshot(response);
        record.setErrorCode(null);
        record.setErrorMessage(null);
        record.setUpdatedAt(now);
        saveIdempotencyRecord(sessionId, idempotencyKey, record);
        log.info("[idem] 更新为完成: sessionId={}, idempotencyKey={}, turnId={}",
                sessionId, idempotencyKey, turnId);
    }

    /**
     * 幂等记录：更新为 FAILED 并保存错误快照。
     */
    public void markIdempotencyFailed(String sessionId,
                                      String idempotencyKey,
                                      String turnId,
                                      String errorCode,
                                      String errorMessage,
                                      AgentResponse failedResponse) {
        IdempotencyRecord record = getIdempotencyRecord(sessionId, idempotencyKey);
        LocalDateTime now = LocalDateTime.now();
        if (record == null) {
            record = IdempotencyRecord.builder()
                    .idempotencyKey(idempotencyKey)
                    .sessionId(sessionId)
                    .turnId(turnId)
                    .createdAt(now)
                    .build();
        }
        record.setTurnId(turnId);
        record.setStatus(IdempotencyStatus.FAILED);
        record.setErrorCode(errorCode);
        record.setErrorMessage(errorMessage);
        record.setResponseSnapshot(failedResponse);
        record.setUpdatedAt(now);
        saveIdempotencyRecord(sessionId, idempotencyKey, record);
        log.warn("[idem] 更新为失败: sessionId={}, idempotencyKey={}, turnId={}, errorCode={}",
                sessionId, idempotencyKey, turnId, errorCode);
    }

    private void saveTurnState(TurnState state) {
        redisTemplate.opsForValue().set(
                buildTurnKey(state.getSessionId(), state.getTurnId()),
                state,
                sessionTimeoutMinutes,
                TimeUnit.MINUTES
        );
    }

    private void saveIdempotencyRecord(String sessionId, String idempotencyKey, IdempotencyRecord record) {
        redisTemplate.opsForValue().set(
                buildIdempotentKey(sessionId, idempotencyKey),
                record,
                sessionTimeoutMinutes,
                TimeUnit.MINUTES
        );
    }

    private String buildTurnKey(String sessionId, String turnId) {
        return turnKeyPrefix + sessionId + ":" + turnId;
    }

    private String buildIdempotentKey(String sessionId, String idempotencyKey) {
        return idempotentKeyPrefix + sessionId + ":" + idempotencyKey;
    }
}

