package com.example.news.aggregation.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.news.aggregation.agent.config.ChatHistoryProperties;
import com.example.news.aggregation.agent.domain.chat.ChatMessageEntity;
import com.example.news.aggregation.agent.domain.chat.UserSessionSummary;
import com.example.news.aggregation.agent.enums.MessageRole;
import com.example.news.aggregation.agent.enums.MessageStatus;
import com.example.news.aggregation.agent.infrastructure.repo.chat.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 对话历史服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.agent.chat-history.enabled", havingValue = "true", matchIfMissing = true)
public class ChatHistoryService {

    private final ChatMessageRepository chatMessageRepository;
    private final ChatHistoryProperties chatHistoryProperties;

    /**
     * 保存用户消息。
     */
    public Long saveUserMessage(String sessionId, String userId, String turnId, String requestHash, String content) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setMessageId(generateMessageId(turnId, 1));
        entity.setTurnId(turnId);
        entity.setSessionId(sessionId);
        entity.setUserId(userId);
        entity.setRequestHash(requestHash);
        entity.setRole(MessageRole.USER.getCode());
        entity.setContent(content);
        entity.setStatus(MessageStatus.SUCCESS.getCode());
        entity.setSeqNo(1);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        chatMessageRepository.insert(entity);
        log.info("[chat-history] 保存用户消息|messionId={} |userId={} |turnId={} |messageId={}",
                sessionId, userId, turnId, entity.getMessageId());
        return entity.getMessageId();
    }

    /**
     * 保存系统回答。
     */
    public Long saveAssistantMessage(String sessionId, String userId, String turnId, String requestHash, String content) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setMessageId(generateMessageId(turnId, 2));
        entity.setTurnId(turnId);
        entity.setSessionId(sessionId);
        entity.setUserId(userId);
        entity.setRequestHash(requestHash);
        entity.setRole(MessageRole.ASSISTANT.getCode());
        entity.setContent(content);
        entity.setStatus(MessageStatus.SUCCESS.getCode());
        entity.setSeqNo(2);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        chatMessageRepository.insert(entity);
        log.info("[chat-history] 保存系统消息|messionId={} |userId={} |turnId={} |messageId={}",
                sessionId, userId, turnId, entity.getMessageId());
        return entity.getMessageId();
    }

    /**
     * 获取指定轮次的历史消息。
     */
    public List<ChatMessageEntity> getTurnMessages(String sessionId, String turnId) {
        return chatMessageRepository.findBySessionIdAndTurnId(sessionId, turnId);
    }

    /**
     * 获取最近N轮对话（用于 LLM 上下文）。
     * 返回格式：["User: xxx", "Assistant: xxx", ...]
     */
    public List<String> getLlmContext(String sessionId, String turnId, int maxTurns) {
        List<ChatMessageEntity> messages = chatMessageRepository.findHistoryExcludingTurn(sessionId, turnId, maxTurns * 2);

        List<String> contextList = new ArrayList<>();
        for (ChatMessageEntity msg : messages) {
            String roleStr = msg.getRole() == MessageRole.USER.getCode() ? "User" : "Assistant";
            contextList.add(roleStr + ": " + msg.getContent());
        }

        return contextList;
    }

    /**
     * 获取会话的完整历史（分页）。
     */
    public List<ChatMessageEntity> getSessionHistory(String sessionId, int pageNum, int pageSize) {
        return chatMessageRepository.findBySessionIdWithPage(sessionId, pageNum, pageSize)
                .getRecords();
    }

    /**
     * 按用户ID获取历史消息（分页）。
     */
    public List<ChatMessageEntity> getUserHistory(String userId, int pageNum, int pageSize) {
        return chatMessageRepository.findByUserIdWithPage(userId, pageNum, pageSize)
                .getRecords();
    }

    /**
     * 按用户查询最近会话（用于 Redis 会话为空时兜底）。
     */
    public List<UserSessionSummary> getRecentSessionsByUser(String userId, int limit) {
        return chatMessageRepository.findRecentSessionsByUserId(userId, limit);
    }

    /**
     * 判断会话是否归属于当前用户（用于会话恢复前的安全校验）。
     */
    public boolean sessionBelongsToUser(String sessionId, String userId) {
        return chatMessageRepository.countBySessionIdAndUserId(sessionId, userId) > 0;
    }

    /**
     * 生成消息ID。
     * 规则：使用时间戳（补零到14位）+ 序号（1=用户，2=系统）
     * 例如：001773848574123 + 01 = 00177384857412301
     */
    private Long generateMessageId(String turnId, int seq) {
        // 使用时间戳（精确到毫秒）生成唯一ID，避免依赖turnId格式
        long timestamp = System.currentTimeMillis();
        // 补零到14位，确保substring不会越界
        String timeStr = String.format("%014d", timestamp);
        String messageIdStr = timeStr + String.format("%02d", seq);
        return Long.parseLong(messageIdStr);
    }

    /**
     * 清理过期对话历史。
     * 按配置的时间间隔执行。
     */
    @Scheduled(cron = "#{@chatHistoryProperties.cleanupCron}")
    public void cleanupExpiredHistory() {
        if (!chatHistoryProperties.isEnabled()) {
            log.debug("[chat-history] 对话历史记录未启用，跳过清理");
            return;
        }

        log.info("[chat-history] 开始清理过期对话历史|retentionDays={}", chatHistoryProperties.getRetentionDays());
        // TODO: 实现基于时间的清理逻辑
        // 1. 计算过期时间点
        // 2. 删除 created_at < 过期时间 的记录
        log.info("[chat-history] 清理完成");
    }
}
