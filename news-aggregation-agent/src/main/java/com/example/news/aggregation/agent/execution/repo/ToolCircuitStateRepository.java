package com.example.news.aggregation.agent.execution.repo;

import com.example.news.aggregation.agent.execution.domain.ToolCircuitStateEntity;
import com.example.news.aggregation.agent.infrastructure.mapper.execution.ExecutionToolCircuitStateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Date;

@Repository
@RequiredArgsConstructor
public class ToolCircuitStateRepository {

    private final ExecutionToolCircuitStateMapper mapper;

    public ToolCircuitStateEntity findByToolAndCapability(String toolName, String capability) {
        return mapper.selectByToolAndCapability(toolName, capability);
    }

    public int insertIgnore(ToolCircuitStateEntity entity) {
        return mapper.insertIgnore(entity);
    }

    public int updateStateWithCas(String toolName,
                                  String capability,
                                  Integer expectedLockVersion,
                                  String state,
                                  Date openUntil,
                                  String halfOpenOwner,
                                  Date ownerLeaseUntil,
                                  String lastReasonCode) {
        return mapper.updateStateWithCas(
                toolName,
                capability,
                expectedLockVersion,
                state,
                openUntil,
                halfOpenOwner,
                ownerLeaseUntil,
                lastReasonCode
        );
    }
}
