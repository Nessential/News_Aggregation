package com.example.news.aggregation.agent.infrastructure.mapper.execution;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.news.aggregation.agent.execution.domain.ExecutionEffectLatchEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ExecutionEffectLatchMapper extends BaseMapper<ExecutionEffectLatchEntity> {

    @Select("""
            SELECT *
            FROM agent_execution_effect_latch
            WHERE effect_key = #{effectKey}
              AND deleted = 0
            LIMIT 1
            """)
    ExecutionEffectLatchEntity selectByEffectKey(@Param("effectKey") String effectKey);

    @Insert("""
            INSERT IGNORE INTO agent_execution_effect_latch(
                effect_key, run_id, step_id, status, provider_trace,
                request_payload_hash, response_digest, error_code, error_message,
                deleted, lock_version, gmt_create, gmt_modified
            ) VALUES (
                #{effectKey}, #{runId}, #{stepId}, #{status}, #{providerTrace},
                #{requestPayloadHash}, #{responseDigest}, #{errorCode}, #{errorMessage},
                #{deleted}, #{lockVersion}, NOW(), NOW()
            )
            """)
    int insertIgnore(ExecutionEffectLatchEntity entity);

    @Update("""
            UPDATE agent_execution_effect_latch
            SET status = #{status},
                provider_trace = #{providerTrace},
                response_digest = #{responseDigest},
                error_code = #{errorCode},
                error_message = #{errorMessage},
                lock_version = lock_version + 1
            WHERE effect_key = #{effectKey}
              AND lock_version = #{expectedLockVersion}
              AND deleted = 0
            """)
    int updateStatusWithCas(@Param("effectKey") String effectKey,
                            @Param("expectedLockVersion") Integer expectedLockVersion,
                            @Param("status") String status,
                            @Param("providerTrace") String providerTrace,
                            @Param("responseDigest") String responseDigest,
                            @Param("errorCode") String errorCode,
                            @Param("errorMessage") String errorMessage);
}
