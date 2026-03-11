package com.example.news.aggregation.agent.infrastructure.mapper.chat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.news.aggregation.agent.domain.chat.ChatMessageEntity;
import com.example.news.aggregation.agent.domain.chat.UserSessionSummary;
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
            ORDER BY seq_no ASC
            """)
    List<ChatMessageEntity> selectBySessionIdAndTurnId(@Param("sessionId") String sessionId,
                                                        @Param("turnId") String turnId);

    @Select("""
            SELECT *
            FROM chat_message
            WHERE session_id = #{sessionId}
              AND turn_id != #{excludeTurnId}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<ChatMessageEntity> selectHistoryExcludingTurn(@Param("sessionId") String sessionId,
                                                       @Param("excludeTurnId") String excludeTurnId,
                                                       @Param("limit") int limit);

    @Select("""
            SELECT session_id AS sessionId,
                   user_id AS userId,
                   MAX(created_at) AS latestAt
            FROM chat_message
            WHERE user_id = #{userId}
              AND session_id IS NOT NULL
              AND session_id != ''
            GROUP BY session_id, user_id
            ORDER BY latestAt DESC
            LIMIT #{limit}
            """)
    List<UserSessionSummary> selectRecentSessionsByUserId(@Param("userId") String userId,
                                                          @Param("limit") int limit);

    @Select("""
            SELECT COUNT(1)
            FROM chat_message
            WHERE session_id = #{sessionId}
              AND user_id = #{userId}
            """)
    int countBySessionIdAndUserId(@Param("sessionId") String sessionId,
                                  @Param("userId") String userId);
}
