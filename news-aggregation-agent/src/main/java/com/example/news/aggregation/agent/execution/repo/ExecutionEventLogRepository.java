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

    public long countByRunId(String runId) {
        return eventLogMapper.countByRunId(runId);
    }

    public List<ExecutionEventLogEntity> listByRunIdAfterEventId(String runId, long afterId, int limit) {
        return eventLogMapper.listByRunIdAfterEventId(runId, afterId, limit);
    }

    public List<ExecutionEventLogEntity> listRecentByRunId(String runId, int limit) {
        return eventLogMapper.listRecentByRunId(runId, limit);
    }

    public ExecutionEventLogEntity findLatestByRunIdAndEventType(String runId, String eventType) {
        return eventLogMapper.findLatestByRunIdAndEventType(runId, eventType);
    }
}
