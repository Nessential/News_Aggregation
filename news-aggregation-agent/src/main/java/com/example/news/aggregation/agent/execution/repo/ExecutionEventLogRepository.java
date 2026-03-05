package com.example.news.aggregation.agent.execution.repo;

import com.example.news.aggregation.agent.execution.domain.ExecutionEventLogEntity;
import com.example.news.aggregation.agent.infrastructure.mapper.execution.ExecutionEventLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class ExecutionEventLogRepository {

    private final ExecutionEventLogMapper eventLogMapper;

    public int insert(ExecutionEventLogEntity entity) {
        return eventLogMapper.insert(entity);
    }

    public List<ExecutionEventLogEntity> listByRunId(String runId) {
        return eventLogMapper.listByRunId(runId);
    }
}
