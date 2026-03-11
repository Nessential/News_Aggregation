package com.example.news.aggregation.agent.domain.chat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Chat message entity.
 */
@Getter
@Setter
@TableName("chat_message")
public class ChatMessageEntity {

    @TableId(type = IdType.INPUT)
    private Long messageId;

    private String turnId;

    private String sessionId;

    private String userId;

    private String requestHash;

    /**
     * 0=user, 1=assistant
     */
    private Integer role;

    /**
     * Plain text content for compatibility.
     */
    private String content;

    /**
     * Markdown snapshot for rendering.
     */
    private String contentMarkdown;

    /**
     * Content format marker: PLAIN/MARKDOWN.
     */
    private String contentFormat;

    /**
     * 0=processing, 1=success, 2=failed
     */
    private Integer status;

    private Integer seqNo;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
