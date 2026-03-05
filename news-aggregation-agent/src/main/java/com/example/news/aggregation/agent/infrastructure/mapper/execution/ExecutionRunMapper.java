package com.example.news.aggregation.agent.infrastructure.mapper.execution;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.news.aggregation.agent.execution.domain.ExecutionRunEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

@Mapper
public interface ExecutionRunMapper extends BaseMapper<ExecutionRunEntity> {

    @Select("""
            SELECT *
            FROM agent_execution_run
            WHERE request_dedupe_key = #{requestDedupeKey}
              AND deleted = 0
            LIMIT 1
            """)
    ExecutionRunEntity selectByRequestDedupeKey(@Param("requestDedupeKey") String requestDedupeKey);

    @Select("""
            SELECT *
            FROM agent_execution_run
            WHERE run_id = #{runId}
              AND deleted = 0
            LIMIT 1
            """)
    ExecutionRunEntity selectByRunId(@Param("runId") String runId);

    @Update("""
            UPDATE agent_execution_run
            SET status = #{toStatus},
                current_step = #{currentStep},
                error_code = #{errorCode},
                error_message = #{errorMessage},
                finished_at = #{finishedAt},
                lock_version = lock_version + 1
            WHERE run_id = #{runId}
              AND deleted = 0
              AND lock_version = #{expectedLockVersion}
            """)
    int updateStatusWithCas(@Param("runId") String runId,
                            @Param("expectedLockVersion") Integer expectedLockVersion,
                            @Param("toStatus") String toStatus,
                            @Param("currentStep") String currentStep,
                            @Param("errorCode") String errorCode,
                            @Param("errorMessage") String errorMessage,
                            @Param("finishedAt") Date finishedAt);

    @Update("""
            UPDATE agent_execution_run
            SET active_plan_version = #{activePlanVersion},
                replan_count_run = replan_count_run + 1,
                lock_version = lock_version + 1
            WHERE run_id = #{runId}
              AND deleted = 0
              AND lock_version = #{expectedLockVersion}
            """)
    int switchActivePlanVersionAndIncreaseReplanCountWithCas(@Param("runId") String runId,
                                                              @Param("expectedLockVersion") Integer expectedLockVersion,
                                                              @Param("activePlanVersion") Integer activePlanVersion);
}
