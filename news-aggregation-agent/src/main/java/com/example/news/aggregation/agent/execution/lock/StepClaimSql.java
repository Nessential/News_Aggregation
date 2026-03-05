package com.example.news.aggregation.agent.execution.lock;

/**
 * Step 运行期 CAS SQL 常量。
 * 使用 MyBatis 参数占位，避免 SQL 漂移与参数错位。
 */
public final class StepClaimSql {

    public static final String CLAIM_PENDING =
            "UPDATE agent_execution_step_run " +
                    "SET status='RUNNING', worker_id=#{workerId}, lease_until=#{leaseUntil}, started_at=IFNULL(started_at, NOW()), " +
                    "lock_version=lock_version+1 " +
                    "WHERE run_id=#{runId} AND step_id=#{stepId} AND status='PENDING' " +
                    "AND lock_version=#{expectedLockVersion} AND deleted=0";

    public static final String TAKEOVER_EXPIRED_RUNNING =
            "UPDATE agent_execution_step_run " +
                    "SET worker_id=#{workerId}, lease_until=#{leaseUntil}, lock_version=lock_version+1 " +
                    "WHERE run_id=#{runId} AND step_id=#{stepId} AND status='RUNNING' AND lease_until < NOW() " +
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
                    "reason_code=#{reasonCode}, error_code=#{errorCode}, error_message=#{errorMessage}, " +
                    "worker_id=NULL, lease_until=NULL, lock_version=lock_version+1 " +
                    "WHERE run_id=#{runId} AND step_id=#{stepId} AND status='RUNNING' " +
                    "AND lock_version=#{expectedLockVersion} AND deleted=0";

    public static final String UPDATE_OUTPUT =
            "UPDATE agent_execution_step_run " +
                    "SET output_json=#{outputJson}, lock_version=lock_version+1 " +
                    "WHERE run_id=#{runId} AND step_id=#{stepId} AND lock_version=#{expectedLockVersion} AND deleted=0";

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

    private StepClaimSql() {
    }
}
