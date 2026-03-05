package com.example.news.aggregation.agent.execution.repo;

import com.example.news.aggregation.agent.execution.domain.ExecutionEffectLatchEntity;
import com.example.news.aggregation.agent.infrastructure.mapper.execution.ExecutionEffectLatchMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ExecutionEffectLatchRepository {

    private final ExecutionEffectLatchMapper effectLatchMapper;

    public ExecutionEffectLatchEntity findByEffectKey(String effectKey) {
        return effectLatchMapper.selectByEffectKey(effectKey);
    }

    public int insertIgnore(ExecutionEffectLatchEntity entity) {
        return effectLatchMapper.insertIgnore(entity);
    }

    public int updateStatusWithCas(String effectKey,
                                   Integer expectedLockVersion,
                                   String status,
                                   String providerTrace,
                                   String responseDigest,
                                   String errorCode,
                                   String errorMessage) {
        return effectLatchMapper.updateStatusWithCas(
                effectKey,
                expectedLockVersion,
                status,
                providerTrace,
                responseDigest,
                errorCode,
                errorMessage
        );
    }
}
