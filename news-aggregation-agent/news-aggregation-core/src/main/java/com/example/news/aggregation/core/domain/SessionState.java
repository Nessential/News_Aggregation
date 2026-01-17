package com.example.news.aggregation.core.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Session状态（存储在Redis）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionState implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 当前FSM状态
     */
    private FSMState currentState;

    /**
     * 对话历史
     */
    @Builder.Default
    private List<Message> history = new ArrayList<>();

    /**
     * 上下文数据（K-V对）
     */
    @Builder.Default
    private java.util.Map<String, Object> context = new java.util.HashMap<>();

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 最后更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 版本号（乐观锁）
     */
    @Builder.Default
    private Long version = 0L;
}