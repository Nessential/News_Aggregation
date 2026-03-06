package com.example.news.aggregation.agent.execution.model;

import com.example.news.aggregation.agent.execution.domain.ExecutionEventLogEntity;
import com.example.news.aggregation.agent.execution.domain.ExecutionRunEntity;
import com.example.news.aggregation.agent.execution.domain.ExecutionStepRunEntity;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 运行回放快照：聚合 run、step_run、event_log 三类持久化数据，
 * 用于排障追溯与离线回放分析。
 */
@Getter
@Builder
public class ExecutionReplaySnapshot {

    private ExecutionRunEntity run;
    private List<ExecutionStepRunEntity> stepRuns;
    private List<ExecutionEventLogEntity> events;

    /** 便于回放接口直接返回摘要字段。 */
    private Integer activePlanVersion;
    private Integer stepCount;
    private Long eventCount;
    private String terminalState;
    private String timelineDigest;
    private String plannerTraceId;
}
