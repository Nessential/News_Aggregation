package com.example.news.aggregation.agent.workflow.selector;

import com.example.news.aggregation.agent.execution.domain.ToolCircuitStateEntity;
import com.example.news.aggregation.agent.execution.repo.ToolCircuitStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * DB-backed circuit state store with lazy row initialization.
 */
@Component
@RequiredArgsConstructor
public class DbToolCircuitStateStore implements ToolCircuitStateStore {

    private final ToolCircuitStateRepository repository;

    @Override
    public ToolCircuitStateEntity getOrCreate(String toolName, String capability) {
        ToolCircuitStateEntity existing = repository.findByToolAndCapability(toolName, capability);
        if (existing != null) {
            return existing;
        }
        ToolCircuitStateEntity init = new ToolCircuitStateEntity();
        init.setToolName(toolName);
        init.setCapability(capability);
        init.setState(ToolCircuitState.CLOSED.name());
        init.setOpenUntil(null);
        init.setHalfOpenOwner(null);
        init.setOwnerLeaseUntil(null);
        init.setLastReasonCode("init_closed");
        init.setDeleted(0);
        init.setLockVersion(0);
        repository.insertIgnore(init);
        return repository.findByToolAndCapability(toolName, capability);
    }

    @Override
    public int updateStateWithCas(String toolName,
                                  String capability,
                                  Integer expectedLockVersion,
                                  ToolCircuitState state,
                                  Date openUntil,
                                  String halfOpenOwner,
                                  Date ownerLeaseUntil,
                                  String lastReasonCode) {
        return repository.updateStateWithCas(
                toolName,
                capability,
                expectedLockVersion == null ? 0 : expectedLockVersion,
                state.name(),
                openUntil,
                halfOpenOwner,
                ownerLeaseUntil,
                lastReasonCode
        );
    }
}

