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
    /** 本次实际选择执行的工具（用于恢复/排障复盘）。 */
    private String selectedTool;
    /** 本次选路原因码（primary_healthy/fallback_tool_open等）。 */
    private String selectionReasonCode;
    /** 熔断状态快照（可截断JSON）。 */
    private String circuitStateSnapshot;
    /** 参与选路的候选工具快照（可截断JSON）。 */
    private String fallbackCandidatesJson;
    private Boolean replanAllowed;
    private Boolean needUserInputOnFailure;
    private String resumeMode;
    private String reasonCode;
    private String errorCode;
    private String errorMessage;
    private Date startedAt;
    private Date finishedAt;
}
