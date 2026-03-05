package com.example.news.aggregation.agent.execution.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.news.aggregation.datasource.domain.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 副作用幂等门闩记录。
 */
@Getter
@Setter
@TableName("agent_execution_effect_latch")
public class ExecutionEffectLatchEntity extends BaseEntity {

    private String effectKey;
    private String runId;
    private String stepId;
    private String status;
    private String providerTrace;
    private String requestPayloadHash;
    private String responseDigest;
    private String errorCode;
    private String errorMessage;
}
