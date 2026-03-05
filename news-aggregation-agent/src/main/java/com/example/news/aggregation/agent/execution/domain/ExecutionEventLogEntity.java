package com.example.news.aggregation.agent.execution.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.news.aggregation.datasource.domain.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 执行事件日志。
 */
@Getter
@Setter
@TableName("agent_execution_event_log")
public class ExecutionEventLogEntity extends BaseEntity {

    private String runId;
    private String stepId;
    private String eventType;
    private Integer eventVersion;
    private String fromState;
    private String toState;
    private String reasonCode;
    private String message;
    private String payloadJson;
}
