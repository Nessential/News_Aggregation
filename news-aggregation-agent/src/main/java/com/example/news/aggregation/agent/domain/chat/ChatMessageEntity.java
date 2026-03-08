package com.example.news.aggregation.agent.domain.chat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 对话消息实体。
 */
@Getter
@Setter
@TableName("chat_message")
public class ChatMessageEntity {

    /**
     * 消息唯一ID（雪花算法）
     */
    @TableId(type = IdType.INPUT)
    private Long messageId;

    /**
     * 对话轮次ID
     */
    private String turnId;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 请求哈希（关联幂等系统）
     */
    private String requestHash;

    /**
     * 角色: 0=用户, 1=系统
     */
    private Integer role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 状态: 0=处理中, 1=成功, 2=失败
     */
    private Integer status;

    /**
     * 消息序号（用于排序）
     */
    private Integer seqNo;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
