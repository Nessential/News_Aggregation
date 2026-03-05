package com.example.news.aggregation.agent.execution.lock;

/**
 * step 运行相关 CAS SQL 常量。
 * Week5 约束：claim/takeover 仅允许命中当前 active_plan_version 的 step。
 */
public final class StepClaimSql {

    public static final String CLAIM_PENDING =
            "UPDATE agent_execution_step_run " +
                    "SET status='RUNNING', worker_id=#{workerId}, lease_until=#{leaseUntil}, started_at=IFNULL(started_at, NOW()), " +
                    "lock_version=lock_version+1 " +
                    "WHERE run_id=#{runId} AND step_id=#{stepId} AND status='PENDING' " +
                    "AND plan_version = (SELECT active_plan_version FROM agent_execution_run WHERE run_id=#{runId} AND deleted=0 LIMIT 1) " +
                    "AND lock_version=#{expectedLockVersion} AND deleted=0";

    public static final String TAKEOVER_EXPIRED_RUNNING =
            "UPDATE agent_execution_step_run " +
                    "SET worker_id=#{workerId}, lease_until=#{leaseUntil}, lock_version=lock_version+1 " +
                    "WHERE run_id=#{runId} AND step_id=#{stepId} AND status='RUNNING' AND lease_until < NOW() " +
                    "AND plan_version = (SELECT active_plan_version FROM agent_execution_run WHERE run_id=#{runId} AND deleted=0 LIMIT 1) " +
                    "AND lock_version=#{expectedLockVersion} AND deleted=0";

    public static final String HEARTBEAT_RUNNING =
            "UPDATE agent_execution_step_run " +
                    "SET lease_until=#{leaseUntil}, lock_version=lock_version+1 " +
                    "WHERE run_id=#{runId} AND step_id=#{stepId} AND status='RUNNING' " +
                    "AND worker_id=#{workerId} AND lock_version=#{expectedLockVersion} AND deleted=0";

    public static final String MARK_SUCCEEDED =
            "UPDATE agent_execution_step_run " +
                    "SET status='SUCCEEDED', output_json=#{outputJson}, reason_code=NULL, error_code=NULL, error_message=NULL, " +
                    "worker_id=NULL, lease_until=NULL, finished_at=#{finishedAt}, lock_version=lock_version+1 " +
                    "WHERE run_id=#{runId} AND step_id=#{stepId} AND status='RUNNING' " +
                    "AND lock_version=#{expectedLockVersion} AND deleted=0";

    public static final String MARK_TERMINAL =
            "UPDATE agent_execution_step_run " +
                    "SET status=#{toStatus}, reason_code=#{reasonCode}, error_code=#{errorCode}, error_message=#{errorMessage}, " +
                    "worker_id=NULL, lease_until=NULL, finished_at=#{finishedAt}, lock_version=lock_version+1 " +
                    "WHERE run_id=#{runId} AND step_id=#{stepId} AND lock_version=#{expectedLockVersion} AND deleted=0";

    public static final String MARK_RETRY_PENDING =
            "UPDATE agent_execution_step_run " +
                    "SET status='PENDING', attempt=attempt+1, reason_code=#{reasonCode}, error_code=#{errorCode}, error_message=#{errorMessage}, " +
                    "worker_id=NULL, lease_until=NULL, lock_version=lock_version+1 " +
                    "WHERE run_id=#{runId} AND step_id=#{stepId} AND status='RUNNING' " +
                    "AND lock_version=#{expectedLockVersion} AND deleted=0";

    public static final String MARK_RETRY_PENDING_SWITCH_CAPABILITY =
            "UPDATE agent_execution_step_run " +
                    "SET status='PENDING', attempt=attempt+1, active_capability_name=#{activeCapabilityName}, " +
                    "selected_tool=#{activeCapabilityName}, selection_reason_code=#{reasonCode}, " +
                    "reason_code=#{reasonCode}, error_code=#{errorCode}, error_message=#{errorMessage}, " +
                    "worker_id=NULL, lease_until=NULL, lock_version=lock_version+1 " +
                    "WHERE run_id=#{runId} AND step_id=#{stepId} AND status='RUNNING' " +
                    "AND lock_version=#{expectedLockVersion} AND deleted=0";

    public static final String UPDATE_SELECTION_SNAPSHOT =
            "UPDATE agent_execution_step_run " +
                    "SET selected_tool=#{selectedTool}, selection_reason_code=#{selectionReasonCode}, " +
                    "circuit_state_snapshot=#{circuitStateSnapshot}, fallback_candidates_json=#{fallbackCandidatesJson}, " +
                    "lock_version=lock_version+1 " +
                    "WHERE run_id=#{runId} AND step_id=#{stepId} " +
                    "AND lock_version=#{expectedLockVersion} AND deleted=0";

    public static final String UPDATE_ACTIVE_SELECTION_SNAPSHOT =
            "UPDATE agent_execution_step_run " +
                    "SET active_capability_name=#{activeCapabilityName}, " +
                    "selected_tool=#{selectedTool}, selection_reason_code=#{selectionReasonCode}, " +
                    "circuit_state_snapshot=#{circuitStateSnapshot}, fallback_candidates_json=#{fallbackCandidatesJson}, " +
                    "lock_version=lock_version+1 " +
                    "WHERE run_id=#{runId} AND step_id=#{stepId} " +
                    "AND lock_version=#{expectedLockVersion} AND deleted=0";

    public static final String UPDATE_OUTPUT =
            "UPDATE agent_execution_step_run " +
                    "SET output_json=#{outputJson}, lock_version=lock_version+1 " +
                    "WHERE run_id=#{runId} AND step_id=#{stepId} AND lock_version=#{expectedLockVersion} AND deleted=0";

    public static final String RECORD_REPLAN_ATTEMPT =
            "UPDATE agent_execution_step_run " +
                    "SET replan_count_step=replan_count_step+1, " +
                    "last_replan_reason_code=#{reasonCode}, " +
                    "change_proof_snapshot=#{changeProofSnapshot}, " +
                    "evidence_snapshot=#{evidenceSnapshot}, " +
                    "replan_decision_action=#{replanDecisionAction}, " +
                    "lock_version=lock_version+1 " +
                    "WHERE run_id=#{runId} AND step_id=#{stepId} " +
                    "AND lock_version=#{expectedLockVersion} AND deleted=0";

    public static final String RECOVER_TO_PENDING =
            "UPDATE agent_execution_step_run " +
                    "SET status='PENDING', worker_id=NULL, lease_until=NULL, recovery_attempt=#{nextRecoveryAttempt}, " +
                    "reason_code=#{reasonCode}, error_code=#{errorCode}, error_message=#{errorMessage}, lock_version=lock_version+1 " +
                    "WHERE run_id=#{runId} AND step_id=#{stepId} AND status='RUNNING' " +
                    "AND lock_version=#{expectedLockVersion} AND deleted=0";

    public static final String RESUME_WAITING_TO_PENDING =
            "UPDATE agent_execution_step_run " +
                    "SET status='PENDING', input_json=#{inputJson}, reason_code=NULL, error_code=NULL, error_message=NULL, " +
                    "worker_id=NULL, lease_until=NULL, lock_version=lock_version+1 " +
                    "WHERE run_id=#{runId} AND step_id=#{stepId} AND status='WAITING' " +
                    "AND lock_version=#{expectedLockVersion} AND deleted=0";

    public static final String SUPERSEDE_PENDING_STEPS_NOT_IN_PLAN_VERSION =
            "UPDATE agent_execution_step_run " +
                    "SET status='SUPERSEDED', reason_code='plan_superseded', error_code='PLAN_SUPERSEDED', " +
                    "error_message='step superseded by active plan version switch', " +
                    "worker_id=NULL, lease_until=NULL, finished_at=IFNULL(finished_at, NOW()), lock_version=lock_version+1 " +
                    "WHERE run_id=#{runId} AND plan_version <> #{activePlanVersion} " +
                    "AND status='PENDING' AND deleted=0";

    private StepClaimSql() {
    }
}
