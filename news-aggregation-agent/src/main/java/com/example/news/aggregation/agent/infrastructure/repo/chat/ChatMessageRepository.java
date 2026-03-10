package com.example.news.aggregation.agent.infrastructure.repo.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.news.aggregation.agent.domain.chat.ChatMessageEntity;
import com.example.news.aggregation.agent.domain.chat.UserSessionSummary;
import com.example.news.aggregation.agent.infrastructure.mapper.chat.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class ChatMessageRepository {

    private final ChatMessageMapper chatMessageMapper;

    public int insert(ChatMessageEntity entity) {
        return chatMessageMapper.insert(entity);
    }

    public int updateById(ChatMessageEntity entity) {
        return chatMessageMapper.updateById(entity);
    }

    public ChatMessageEntity findByMessageId(Long messageId) {
        return chatMessageMapper.selectById(messageId);
    }

    public List<ChatMessageEntity> findBySessionIdAndTurnId(String sessionId, String turnId) {
        return chatMessageMapper.selectBySessionIdAndTurnId(sessionId, turnId);
    }

    public List<ChatMessageEntity> findRecentBySessionId(String sessionId, int limit) {
        return chatMessageMapper.selectRecentBySessionId(sessionId, limit);
    }

    public List<ChatMessageEntity> findHistoryExcludingTurn(String sessionId, String excludeTurnId, int limit) {
        return chatMessageMapper.selectHistoryExcludingTurn(sessionId, excludeTurnId, limit);
    }

    public List<UserSessionSummary> findRecentSessionsByUserId(String userId, int limit) {
        return chatMessageMapper.selectRecentSessionsByUserId(userId, limit);
    }

    public IPage<ChatMessageEntity> findBySessionIdWithPage(String sessionId, int pageNum, int pageSize) {
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessageEntity::getSessionId, sessionId)
               .orderByDesc(ChatMessageEntity::getCreatedAt);
        return chatMessageMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    public IPage<ChatMessageEntity> findByUserIdWithPage(String userId, int pageNum, int pageSize) {
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessageEntity::getUserId, userId)
               .orderByDesc(ChatMessageEntity::getCreatedAt);
        return chatMessageMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    public int countBySessionId(String sessionId) {
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessageEntity::getSessionId, sessionId);
        return chatMessageMapper.selectCount(wrapper).intValue();
    }

    public int countBySessionIdAndUserId(String sessionId, String userId) {
        return chatMessageMapper.countBySessionIdAndUserId(sessionId, userId);
    }

    public int deleteBySessionId(String sessionId) {
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessageEntity::getSessionId, sessionId);
        return chatMessageMapper.delete(wrapper);
    }
}
