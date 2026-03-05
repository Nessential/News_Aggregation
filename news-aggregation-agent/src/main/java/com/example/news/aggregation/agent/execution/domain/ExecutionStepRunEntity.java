package com.example.news.aggregation.agent.execution.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.news.aggregation.datasource.domain.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 步骤运行记录。
 */
@Getter
@Setter
@TableName("agent_execution_step_run")
public class ExecutionStepRunEntity extends BaseEntity {

    private String runId;
    private String stepId;
    private String capabilityName;
    private String activeCapabilityName;
    private String status;
    private Integer attempt;
    private Integer maxRetries;
    private Integer recoveryAttempt;
    private Integer maxRecoveryAttempts;
    private String workerId;
    private Date leaseUntil;
    private String dependsOnJson;
    private String inputJson;
    private String outputJson;
    private String sideEffect;
    private String fallbackToolsJson;
    private Boolean replanAllowed;
    private Boolean needUserInputOnFailure;
    private String resumeMode;
    private String reasonCode;
    private String errorCode;
    private String errorMessage;
    private Date startedAt;
    private Date finishedAt;
}
