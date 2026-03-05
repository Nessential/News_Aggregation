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
        log.info("[turn] 创建运行中turn|sessionId={} |turnId={}", sessionId, turnId);
        return state;
    }

    public void bindRunId(String sessionId, String turnId, String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        TurnState current = getTurnState(sessionId, turnId);
        if (current == null) {
            current = createRunningTurn(sessionId, turnId, "");
        }
        current.setRunId(runId);
        current.setUpdatedAt(LocalDateTime.now());
        saveTurnState(current);
        log.info("[turn] 绑定runId|sessionId={} |turnId={} |runId={}", sessionId, turnId, runId);
    }

    public String getBoundRunId(String sessionId, String turnId) {
        TurnState turnState = getTurnState(sessionId, turnId);
        return turnState == null ? null : turnState.getRunId();
    }

    public void updateFsmState(String sessionId, String turnId, ConversationState fsmState) {
        TurnState current = getTurnState(sessionId, turnId);
        if (current == null) {
            return;
        }
        current.setFsmState(fsmState);
        current.setUpdatedAt(LocalDateTime.now());
        saveTurnState(current);
    }

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
        log.info("[turn] turn完成|sessionId={} |turnId={}", sessionId, turnId);
    }

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
        log.warn("[turn] turn失败|sessionId={} |turnId={} |errorCode={}", sessionId, turnId, errorCode);
    }

    public TurnState getTurnState(String sessionId, String turnId) {
        return (TurnState) redisTemplate.opsForValue().get(buildTurnKey(sessionId, turnId));
    }

    public IdempotencyRecord getIdempotencyRecord(String sessionId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return (IdempotencyRecord) redisTemplate.opsForValue().get(buildIdempotentKey(sessionId, idempotencyKey));
    }

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
            log.info("[idem] 创建IN_PROGRESS记录|sessionId={} |idempotencyKey={} |turnId={}",
                    sessionId, idempotencyKey, turnId);
        }
        return success;
    }

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
        log.info("[idem] 标记DONE|sessionId={} |idempotencyKey={} |turnId={}",
                sessionId, idempotencyKey, turnId);
    }

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
        log.warn("[idem] 标记FAILED|sessionId={} |idempotencyKey={} |turnId={} |errorCode={}",
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
