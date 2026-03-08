package com.example.news.aggregation.agent.infrastructure.mapper.chat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.news.aggregation.agent.domain.chat.ChatMessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {

    @Select("""
            SELECT *
            FROM chat_message
            WHERE session_id = #{sessionId}
              AND deleted = 0
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<ChatMessageEntity> selectRecentBySessionId(@Param("sessionId") String sessionId,
                                                     @Param("limit") int limit);

    @Select("""
            SELECT *
            FROM chat_message
            WHERE session_id = #{sessionId}
              AND turn_id = #{turnId}
              AND deleted = 0
            ORDER BY seq_no ASC
            """)
    List<ChatMessageEntity> selectBySessionIdAndTurnId(@Param("sessionId") String sessionId,
                                                        @Param("turnId") String turnId);

    @Select("""
            SELECT *
            FROM chat_message
            WHERE session_id = #{sessionId}
              AND deleted = 0
              AND turn_id != #{excludeTurnId}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<ChatMessageEntity> selectHistoryExcludingTurn(@Param("sessionId") String sessionId,
                                                       @Param("excludeTurnId") String excludeTurnId,
                                                       @Param("limit") int limit);
}
