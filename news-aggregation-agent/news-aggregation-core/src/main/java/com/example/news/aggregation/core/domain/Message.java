package com.example.news.aggregation.core.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对话消息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 角色：USER/AGENT
     */
    private String role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 时间戳
     */
    private LocalDateTime timestamp;

    /**
     * 元数据（可选）
     */
    private java.util.Map<String, Object> metadata;
}