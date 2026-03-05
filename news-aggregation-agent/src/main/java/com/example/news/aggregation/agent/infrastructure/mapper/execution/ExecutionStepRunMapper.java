package com.example.news.aggregation.agent.infrastructure.mapper.execution;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.news.aggregation.agent.execution.domain.ExecutionStepRunEntity;
import com.example.news.aggregation.agent.execution.lock.StepClaimSql;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

@Mapper
public interface ExecutionStepRunMapper extends BaseMapper<ExecutionStepRunEntity> {

    @Select("""
            SELECT *
            FROM agent_execution_step_run
            WHERE run_id = #{runId}
              AND step_id = #{stepId}
              AND deleted = 0
            LIMIT 1
            """)
    ExecutionStepRunEntity selectByRunIdAndStepId(@Param("runId") String runId,
                                                  @Param("stepId") String stepId);

    @Select("""
            SELECT *
            FROM agent_execution_step_run
            WHERE run_id = #{runId}
              AND deleted = 0
            ORDER BY id ASC
            """)
    List<ExecutionStepRunEntity> selectByRunId(@Param("runId") String runId);

    @Insert("""
            INSERT IGNORE INTO agent_execution_step_run(
                run_id, step_id, capability_name, active_capability_name, status,
                attempt, max_retries, recovery_attempt, max_recovery_attempts,
                worker_id, lease_until, depends_on_json, input_json, output_json,
                side_effect, fallback_tools_json, selected_tool, selection_reason_code,
                circuit_state_snapshot, fallback_candidates_json,
                replan_allowed, need_user_input_on_failure,
                resume_mode, reason_code, error_code, error_message,
                started_at, finished_at, deleted, lock_version, gmt_create, gmt_modified
            ) VALUES (
                #{runId}, #{stepId}, #{capabilityName}, #{activeCapabilityName}, #{status},
                #{attempt}, #{maxRetries}, #{recoveryAttempt}, #{maxRecoveryAttempts},
                #{workerId}, #{leaseUntil}, #{dependsOnJson}, #{inputJson}, #{outputJson},
                #{sideEffect}, #{fallbackToolsJson}, #{selectedTool}, #{selectionReasonCode},
                #{circuitStateSnapshot}, #{fallbackCandidatesJson},
                #{replanAllowed}, #{needUserInputOnFailure},
                #{resumeMode}, #{reasonCode}, #{errorCode}, #{errorMessage},
                #{startedAt}, #{finishedAt}, #{deleted}, #{lockVersion}, NOW(), NOW()
            )
            """)
    int insertIgnore(ExecutionStepRunEntity entity);

    @Update(StepClaimSql.CLAIM_PENDING)
    int claimPendingWithCas(@Param("runId") String runId,
                            @Param("stepId") String stepId,
                            @Param("expectedLockVersion") Integer expectedLockVersion,
                            @Param("workerId") String workerId,
                            @Param("leaseUntil") Date leaseUntil);

    @Update(StepClaimSql.TAKEOVER_EXPIRED_RUNNING)
    int takeoverExpiredRunningWithCas(@Param("runId") String runId,
                                      @Param("stepId") String stepId,
                                      @Param("expectedLockVersion") Integer expectedLockVersion,
                                      @Param("workerId") String workerId,
                                      @Param("leaseUntil") Date leaseUntil);

    @Update(StepClaimSql.HEARTBEAT_RUNNING)
    int heartbeatWithCas(@Param("runId") String runId,
                         @Param("stepId") String stepId,
                         @Param("expectedLockVersion") Integer expectedLockVersion,
                         @Param("workerId") String workerId,
                         @Param("leaseUntil") Date leaseUntil);

    @Update(StepClaimSql.MARK_SUCCEEDED)
    int markSucceededWithCas(@Param("runId") String runId,
                             @Param("stepId") String stepId,
                             @Param("expectedLockVersion") Integer expectedLockVersion,
                             @Param("outputJson") String outputJson,
                             @Param("finishedAt") Date finishedAt);

    @Update(StepClaimSql.MARK_TERMINAL)
    int markTerminalWithCas(@Param("runId") String runId,
                            @Param("stepId") String stepId,
                            @Param("expectedLockVersion") Integer expectedLockVersion,
                            @Param("toStatus") String toStatus,
                            @Param("reasonCode") String reasonCode,
                            @Param("errorCode") String errorCode,
                            @Param("errorMessage") String errorMessage,
                            @Param("finishedAt") Date finishedAt);

    @Update(StepClaimSql.MARK_RETRY_PENDING)
    int markRetryPendingWithCas(@Param("runId") String runId,
                                @Param("stepId") String stepId,
                                @Param("expectedLockVersion") Integer expectedLockVersion,
                                @Param("reasonCode") String reasonCode,
                                @Param("errorCode") String errorCode,
                                @Param("errorMessage") String errorMessage);

    @Update(StepClaimSql.MARK_RETRY_PENDING_SWITCH_CAPABILITY)
    int markRetryPendingSwitchCapabilityWithCas(@Param("runId") String runId,
                                                @Param("stepId") String stepId,
                                                @Param("expectedLockVersion") Integer expectedLockVersion,
                                                @Param("activeCapabilityName") String activeCapabilityName,
                                                @Param("reasonCode") String reasonCode,
                                                @Param("errorCode") String errorCode,
                                                @Param("errorMessage") String errorMessage);

    @Update(StepClaimSql.UPDATE_OUTPUT)
    int updateOutputWithCas(@Param("runId") String runId,
                            @Param("stepId") String stepId,
                            @Param("expectedLockVersion") Integer expectedLockVersion,
                            @Param("outputJson") String outputJson);

    @Update(StepClaimSql.UPDATE_SELECTION_SNAPSHOT)
    int updateSelectionSnapshotWithCas(@Param("runId") String runId,
                                       @Param("stepId") String stepId,
                                       @Param("expectedLockVersion") Integer expectedLockVersion,
                                       @Param("selectedTool") String selectedTool,
                                       @Param("selectionReasonCode") String selectionReasonCode,
                                       @Param("circuitStateSnapshot") String circuitStateSnapshot,
                                       @Param("fallbackCandidatesJson") String fallbackCandidatesJson);

    @Update(StepClaimSql.UPDATE_ACTIVE_SELECTION_SNAPSHOT)
    int updateActiveSelectionSnapshotWithCas(@Param("runId") String runId,
                                             @Param("stepId") String stepId,
                                             @Param("expectedLockVersion") Integer expectedLockVersion,
                                             @Param("activeCapabilityName") String activeCapabilityName,
                                             @Param("selectedTool") String selectedTool,
                                             @Param("selectionReasonCode") String selectionReasonCode,
                                             @Param("circuitStateSnapshot") String circuitStateSnapshot,
                                             @Param("fallbackCandidatesJson") String fallbackCandidatesJson);

    @Select("""
            SELECT *
            FROM agent_execution_step_run
            WHERE status = 'RUNNING'
              AND lease_until IS NOT NULL
              AND lease_until < NOW()
              AND deleted = 0
            ORDER BY lease_until ASC
            LIMIT #{limit}
            """)
    List<ExecutionStepRunEntity> listExpiredRunning(@Param("limit") Integer limit);

    @Update(StepClaimSql.RECOVER_TO_PENDING)
    int recoverToPendingWithCas(@Param("runId") String runId,
                                @Param("stepId") String stepId,
                                @Param("expectedLockVersion") Integer expectedLockVersion,
                                @Param("nextRecoveryAttempt") Integer nextRecoveryAttempt,
                                @Param("reasonCode") String reasonCode,
                                @Param("errorCode") String errorCode,
                                @Param("errorMessage") String errorMessage);

    @Update(StepClaimSql.RESUME_WAITING_TO_PENDING)
    int resumeWaitingToPendingWithCas(@Param("runId") String runId,
                                      @Param("stepId") String stepId,
                                      @Param("expectedLockVersion") Integer expectedLockVersion,
                                      @Param("inputJson") String inputJson);
}
