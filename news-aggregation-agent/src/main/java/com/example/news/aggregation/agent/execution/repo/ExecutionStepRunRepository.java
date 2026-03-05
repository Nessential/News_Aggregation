package com.example.news.aggregation.agent.execution.repo;

import com.example.news.aggregation.agent.execution.domain.ExecutionStepRunEntity;
import com.example.news.aggregation.agent.infrastructure.mapper.execution.ExecutionStepRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ExecutionStepRunRepository {

    private final ExecutionStepRunMapper stepRunMapper;

    public ExecutionStepRunEntity findByRunIdAndStepId(String runId, String stepId) {
        return stepRunMapper.selectByRunIdAndStepId(runId, stepId);
    }

    public List<ExecutionStepRunEntity> findByRunId(String runId) {
        return stepRunMapper.selectByRunId(runId);
    }

    public int insertIgnore(ExecutionStepRunEntity entity) {
        return stepRunMapper.insertIgnore(entity);
    }

    public int claimPendingWithCas(String runId,
                                   String stepId,
                                   Integer expectedLockVersion,
                                   String workerId,
                                   Date leaseUntil) {
        return stepRunMapper.claimPendingWithCas(runId, stepId, expectedLockVersion, workerId, leaseUntil);
    }

    public int takeoverExpiredRunningWithCas(String runId,
                                             String stepId,
                                             Integer expectedLockVersion,
                                             String workerId,
                                             Date leaseUntil) {
        return stepRunMapper.takeoverExpiredRunningWithCas(runId, stepId, expectedLockVersion, workerId, leaseUntil);
    }

    public int heartbeatWithCas(String runId,
                                String stepId,
                                Integer expectedLockVersion,
                                String workerId,
                                Date leaseUntil) {
        return stepRunMapper.heartbeatWithCas(runId, stepId, expectedLockVersion, workerId, leaseUntil);
    }

    public int markSucceededWithCas(String runId,
                                    String stepId,
                                    Integer expectedLockVersion,
                                    String outputJson,
                                    Date finishedAt) {
        return stepRunMapper.markSucceededWithCas(runId, stepId, expectedLockVersion, outputJson, finishedAt);
    }

    public int markTerminalWithCas(String runId,
                                   String stepId,
                                   Integer expectedLockVersion,
                                   String toStatus,
                                   String reasonCode,
                                   String errorCode,
                                   String errorMessage,
                                   Date finishedAt) {
        return stepRunMapper.markTerminalWithCas(
                runId,
                stepId,
                expectedLockVersion,
                toStatus,
                reasonCode,
                errorCode,
                errorMessage,
                finishedAt
        );
    }

    public int markRetryPendingWithCas(String runId,
                                       String stepId,
                                       Integer expectedLockVersion,
                                       String reasonCode,
                                       String errorCode,
                                       String errorMessage) {
        return stepRunMapper.markRetryPendingWithCas(
                runId,
                stepId,
                expectedLockVersion,
                reasonCode,
                errorCode,
                errorMessage
        );
    }

    public int markRetryPendingSwitchCapabilityWithCas(String runId,
                                                       String stepId,
                                                       Integer expectedLockVersion,
                                                       String activeCapabilityName,
                                                       String reasonCode,
                                                       String errorCode,
                                                       String errorMessage) {
        return stepRunMapper.markRetryPendingSwitchCapabilityWithCas(
                runId,
                stepId,
                expectedLockVersion,
                activeCapabilityName,
                reasonCode,
                errorCode,
                errorMessage
        );
    }

    public int updateOutputWithCas(String runId,
                                   String stepId,
                                   Integer expectedLockVersion,
                                   String outputJson) {
        return stepRunMapper.updateOutputWithCas(runId, stepId, expectedLockVersion, outputJson);
    }

    public List<ExecutionStepRunEntity> listExpiredRunning(Integer limit) {
        return stepRunMapper.listExpiredRunning(limit);
    }

    public int recoverToPendingWithCas(String runId,
                                       String stepId,
                                       Integer expectedLockVersion,
                                       Integer nextRecoveryAttempt,
                                       String reasonCode,
                                       String errorCode,
                                       String errorMessage) {
        return stepRunMapper.recoverToPendingWithCas(
                runId,
                stepId,
                expectedLockVersion,
                nextRecoveryAttempt,
                reasonCode,
                errorCode,
                errorMessage
        );
    }

    public int resumeWaitingToPendingWithCas(String runId,
                                             String stepId,
                                             Integer expectedLockVersion,
                                             String inputJson) {
        return stepRunMapper.resumeWaitingToPendingWithCas(
                runId,
                stepId,
                expectedLockVersion,
                inputJson
        );
    }
}
