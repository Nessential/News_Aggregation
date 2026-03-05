package com.example.news.aggregation.agent.execution.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.news.aggregation.datasource.domain.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * step 运行记录。
 */
@Getter
@Setter
@TableName("agent_execution_step_run")
public class ExecutionStepRunEntity extends BaseEntity {

    private String runId;
    private String stepId;
    /** step 所属计划版本。 */
    private Integer planVersion;

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

    /** 本次实际执行工具（用于恢复/回放排障）。 */
    private String selectedTool;
    /** 选路原因码（如 primary_healthy / fallback_tool_open）。 */
    private String selectionReasonCode;
    /** 熔断状态快照（可截断 JSON）。 */
    private String circuitStateSnapshot;
    /** 候选工具快照（可截断 JSON）。 */
    private String fallbackCandidatesJson;

    /** step 级重规划次数。 */
    private Integer replanCountStep;
    /** 最近一次重规划原因码。 */
    private String lastReplanReasonCode;
    /** 变化证明快照（可截断 JSON）。 */
    private String changeProofSnapshot;
    /** 证据快照（可截断 JSON）。 */
    private String evidenceSnapshot;
    /** 决策动作快照（REPLAN/ABORT/WAIT 等）。 */
    private String replanDecisionAction;

    private Boolean replanAllowed;
    private Boolean needUserInputOnFailure;
    private String resumeMode;
    private String reasonCode;
    private String errorCode;
    private String errorMessage;
    private Date startedAt;
    private Date finishedAt;
}
