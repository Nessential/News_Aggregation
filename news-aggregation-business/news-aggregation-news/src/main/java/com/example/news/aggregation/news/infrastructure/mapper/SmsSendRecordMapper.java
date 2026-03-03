package com.example.news.aggregation.news.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.news.aggregation.news.domain.entity.SmsSendRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

@Mapper
public interface SmsSendRecordMapper extends BaseMapper<SmsSendRecord> {

    @Update("""
            UPDATE sms_send_record
            SET state = #{toState},
                provider_code = #{providerCode},
                provider_message = #{providerMessage},
                provider_request_id = #{providerRequestId},
                send_success_time = #{sendSuccessTime},
                lock_version = lock_version + 1
            WHERE id = #{id}
              AND deleted = 0
              AND state = #{fromState}
              AND lock_version = #{expectedLockVersion}
            """)
    int updateStateWithCas(@Param("id") Long id,
                           @Param("expectedLockVersion") Integer expectedLockVersion,
                           @Param("fromState") String fromState,
                           @Param("toState") String toState,
                           @Param("providerCode") String providerCode,
                           @Param("providerMessage") String providerMessage,
                           @Param("providerRequestId") String providerRequestId,
                           @Param("sendSuccessTime") Date sendSuccessTime);
}
