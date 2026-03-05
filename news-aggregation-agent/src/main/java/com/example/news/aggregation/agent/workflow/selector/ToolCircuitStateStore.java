package com.example.news.aggregation.agent.workflow.selector;

import com.example.news.aggregation.agent.execution.domain.ToolCircuitStateEntity;

import java.util.Date;

/**
 * Global storage abstraction for tool circuit state.
 */
public interface ToolCircuitStateStore {

    ToolCircuitStateEntity getOrCreate(String toolName, String capability);

    int updateStateWithCas(String toolName,
                           String capability,
                           Integer expectedLockVersion,
                           ToolCircuitState state,
                           Date openUntil,
                           String halfOpenOwner,
                           Date ownerLeaseUntil,
                           String lastReasonCode);
}

