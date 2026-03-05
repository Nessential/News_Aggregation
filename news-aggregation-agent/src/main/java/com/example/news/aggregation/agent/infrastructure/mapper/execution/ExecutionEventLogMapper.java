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
}
