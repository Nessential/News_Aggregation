package com.example.news.aggregation.agent.infrastructure.mapper.execution;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.news.aggregation.agent.execution.domain.ExecutionEventLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExecutionEventLogMapper extends BaseMapper<ExecutionEventLogEntity> {

    @Select("""
            SELECT *
            FROM agent_execution_event_log
            WHERE run_id = #{runId}
              AND deleted = 0
            ORDER BY id ASC
            """)
    List<ExecutionEventLogEntity> listByRunId(@Param("runId") String runId);

    @Select("""
            SELECT COUNT(1)
            FROM agent_execution_event_log
            WHERE run_id = #{runId}
              AND deleted = 0
            """)
    long countByRunId(@Param("runId") String runId);

    @Select("""
            SELECT *
            FROM agent_execution_event_log
            WHERE run_id = #{runId}
              AND deleted = 0
              AND id > #{afterId}
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    List<ExecutionEventLogEntity> listByRunIdAfterEventId(@Param("runId") String runId,
                                                           @Param("afterId") long afterId,
                                                           @Param("limit") int limit);

    @Select("""
            SELECT *
            FROM (
                SELECT *
                FROM agent_execution_event_log
                WHERE run_id = #{runId}
                  AND deleted = 0
                ORDER BY id DESC
                LIMIT #{limit}
            ) t
            ORDER BY t.id ASC
            """)
    List<ExecutionEventLogEntity> listRecentByRunId(@Param("runId") String runId,
                                                    @Param("limit") int limit);

    @Select("""
            SELECT *
            FROM agent_execution_event_log
            WHERE run_id = #{runId}
              AND event_type = #{eventType}
              AND deleted = 0
            ORDER BY id DESC
            LIMIT 1
            """)
    ExecutionEventLogEntity findLatestByRunIdAndEventType(@Param("runId") String runId,
                                                           @Param("eventType") String eventType);
}
