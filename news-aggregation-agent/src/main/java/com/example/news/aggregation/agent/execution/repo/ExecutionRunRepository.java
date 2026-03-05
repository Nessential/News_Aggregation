package com.example.news.aggregation.agent.execution.repo;

import com.example.news.aggregation.agent.execution.domain.ExecutionRunEntity;
import com.example.news.aggregation.agent.infrastructure.mapper.execution.ExecutionRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Date;

@Repository
@RequiredArgsConstructor
public class ExecutionRunRepository {

    private final ExecutionRunMapper runMapper;

    public ExecutionRunEntity findByRequestDedupeKey(String requestDedupeKey) {
        return runMapper.selectByRequestDedupeKey(requestDedupeKey);
    }

    public ExecutionRunEntity findByRunId(String runId) {
        return runMapper.selectByRunId(runId);
    }

    public int insert(ExecutionRunEntity entity) {
        return runMapper.insert(entity);
    }

    public int updateStatusWithCas(String runId,
                                   Integer expectedLockVersion,
                                   String toStatus,
                                   String currentStep,
                                   String errorCode,
                                   String errorMessage,
                                   Date finishedAt) {
        return runMapper.updateStatusWithCas(
                runId,
                expectedLockVersion,
                toStatus,
                currentStep,
                errorCode,
                errorMessage,
                finishedAt
        );
    }

    public int switchActivePlanVersionAndIncreaseReplanCountWithCas(String runId,
                                                                     Integer expectedLockVersion,
                                                                     Integer activePlanVersion) {
        return runMapper.switchActivePlanVersionAndIncreaseReplanCountWithCas(
                runId,
                expectedLockVersion,
                activePlanVersion
        );
    }
}
