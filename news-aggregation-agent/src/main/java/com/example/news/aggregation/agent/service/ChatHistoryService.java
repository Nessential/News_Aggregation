package com.example.news.aggregation.agent.service;

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

/**
 * Chat history service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.agent.chat-history.enabled", havingValue = "true", matchIfMissing = true)
public class ChatHistoryService {

    private static final String FORMAT_PLAIN = "PLAIN";
    private static final String FORMAT_MARKDOWN = "MARKDOWN";

    private final ChatMessageRepository chatMessageRepository;
    private final ChatHistoryProperties chatHistoryProperties;

    public Long saveUserMessage(String sessionId, String userId, String turnId, String requestHash, String content) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setMessageId(generateMessageId(1));
        entity.setTurnId(turnId);
        entity.setSessionId(sessionId);
        entity.setUserId(userId);
        entity.setRequestHash(requestHash);
        entity.setRole(MessageRole.USER.getCode());
        entity.setContent(content);
        entity.setContentMarkdown(null);
        entity.setContentFormat(FORMAT_PLAIN);
        entity.setStatus(MessageStatus.SUCCESS.getCode());
        entity.setSeqNo(1);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        chatMessageRepository.insert(entity);
        log.info("[chat-history] saved user message|sessionId={} |userId={} |turnId={} |messageId={}",
                sessionId, userId, turnId, entity.getMessageId());
        return entity.getMessageId();
    }

    public Long saveAssistantMessage(String sessionId,
                                     String userId,
                                     String turnId,
                                     String requestHash,
                                     String content) {
        return saveAssistantMessage(sessionId, userId, turnId, requestHash, content, null);
    }

    public Long saveAssistantMessage(String sessionId,
                                     String userId,
                                     String turnId,
                                     String requestHash,
                                     String content,
                                     String contentMarkdown) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setMessageId(generateMessageId(2));
        entity.setTurnId(turnId);
        entity.setSessionId(sessionId);
        entity.setUserId(userId);
        entity.setRequestHash(requestHash);
        entity.setRole(MessageRole.ASSISTANT.getCode());
        entity.setContent(content);

        if (contentMarkdown != null && !contentMarkdown.isBlank()) {
            entity.setContentMarkdown(contentMarkdown);
            entity.setContentFormat(FORMAT_MARKDOWN);
        } else {
            entity.setContentMarkdown(null);
            entity.setContentFormat(FORMAT_PLAIN);
        }

        entity.setStatus(MessageStatus.SUCCESS.getCode());
        entity.setSeqNo(2);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        chatMessageRepository.insert(entity);
        log.info("[chat-history] saved assistant message|sessionId={} |userId={} |turnId={} |messageId={} |format={}",
                sessionId, userId, turnId, entity.getMessageId(), entity.getContentFormat());
        return entity.getMessageId();
    }

    public List<ChatMessageEntity> getTurnMessages(String sessionId, String turnId) {
        return fillMarkdownFallback(chatMessageRepository.findBySessionIdAndTurnId(sessionId, turnId));
    }

    public List<String> getLlmContext(String sessionId, String turnId, int maxTurns) {
        List<ChatMessageEntity> messages = chatMessageRepository.findHistoryExcludingTurn(sessionId, turnId, maxTurns * 2);

        List<String> contextList = new ArrayList<>();
        for (ChatMessageEntity msg : messages) {
            String roleStr = msg.getRole() == MessageRole.USER.getCode() ? "User" : "Assistant";
            contextList.add(roleStr + ": " + msg.getContent());
        }
        return contextList;
    }

    public List<ChatMessageEntity> getSessionHistory(String sessionId, int pageNum, int pageSize) {
        return fillMarkdownFallback(chatMessageRepository.findBySessionIdWithPage(sessionId, pageNum, pageSize).getRecords());
    }

    public List<ChatMessageEntity> getUserHistory(String userId, int pageNum, int pageSize) {
        return fillMarkdownFallback(chatMessageRepository.findByUserIdWithPage(userId, pageNum, pageSize).getRecords());
    }

    public List<UserSessionSummary> getRecentSessionsByUser(String userId, int limit) {
        return chatMessageRepository.findRecentSessionsByUserId(userId, limit);
    }

    public boolean sessionBelongsToUser(String sessionId, String userId) {
        return chatMessageRepository.countBySessionIdAndUserId(sessionId, userId) > 0;
    }

    private List<ChatMessageEntity> fillMarkdownFallback(List<ChatMessageEntity> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        for (ChatMessageEntity msg : messages) {
            if (msg == null) {
                continue;
            }
            if (msg.getContentMarkdown() == null && msg.getRole() != null && msg.getRole() == MessageRole.ASSISTANT.getCode()) {
                msg.setContentMarkdown(msg.getContent());
                if (msg.getContentFormat() == null || msg.getContentFormat().isBlank()) {
                    msg.setContentFormat(FORMAT_PLAIN);
                }
            }
        }
        return messages;
    }

    private Long generateMessageId(int seq) {
        long timestamp = System.currentTimeMillis();
        String timeStr = String.format("%014d", timestamp);
        String messageIdStr = timeStr + String.format("%02d", seq);
        return Long.parseLong(messageIdStr);
    }

    @Scheduled(cron = "#{@chatHistoryProperties.cleanupCron}")
    public void cleanupExpiredHistory() {
        if (!chatHistoryProperties.isEnabled()) {
            log.debug("[chat-history] history disabled, skip cleanup");
            return;
        }
        log.info("[chat-history] start cleanup|retentionDays={}", chatHistoryProperties.getRetentionDays());
        log.info("[chat-history] cleanup done");
    }
}
