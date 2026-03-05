package com.example.news.aggregation.agent.infrastructure.mapper.execution;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.news.aggregation.agent.execution.domain.ToolCircuitStateEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

@Mapper
public interface ExecutionToolCircuitStateMapper extends BaseMapper<ToolCircuitStateEntity> {

    @Select("""
            SELECT *
            FROM agent_tool_circuit_state
            WHERE tool_name = #{toolName}
              AND capability = #{capability}
              AND deleted = 0
            LIMIT 1
            """)
    ToolCircuitStateEntity selectByToolAndCapability(@Param("toolName") String toolName,
                                                     @Param("capability") String capability);

    @Insert("""
            INSERT IGNORE INTO agent_tool_circuit_state(
                tool_name, capability, state, open_until, half_open_owner, owner_lease_until, last_reason_code,
                deleted, lock_version, gmt_create, gmt_modified
            ) VALUES (
                #{toolName}, #{capability}, #{state}, #{openUntil}, #{halfOpenOwner}, #{ownerLeaseUntil}, #{lastReasonCode},
                #{deleted}, #{lockVersion}, NOW(), NOW()
            )
            """)
    int insertIgnore(ToolCircuitStateEntity entity);

    @Update("""
            UPDATE agent_tool_circuit_state
            SET state = #{state},
                open_until = #{openUntil},
                half_open_owner = #{halfOpenOwner},
                owner_lease_until = #{ownerLeaseUntil},
                last_reason_code = #{lastReasonCode},
                lock_version = lock_version + 1
            WHERE tool_name = #{toolName}
              AND capability = #{capability}
              AND lock_version = #{expectedLockVersion}
              AND deleted = 0
            """)
    int updateStateWithCas(@Param("toolName") String toolName,
                           @Param("capability") String capability,
                           @Param("expectedLockVersion") Integer expectedLockVersion,
                           @Param("state") String state,
                           @Param("openUntil") Date openUntil,
                           @Param("halfOpenOwner") String halfOpenOwner,
                           @Param("ownerLeaseUntil") Date ownerLeaseUntil,
                           @Param("lastReasonCode") String lastReasonCode);
}
