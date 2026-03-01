package com.example.news.aggregation.agent.dto;

import com.example.news.aggregation.agent.domain.Constraints;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话请求 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    /** 会话ID */
    private String sessionId;
    /** 用户ID */
    private String userId;
    /** 本轮ID，未传时由服务端生成 */
    private String turnId;
    /** 幂等键，未传时可默认使用 sessionId + turnId */
    private String idempotencyKey;
    /** 用户问题 */
    private String query;
    /** 约束条件 */
    private Constraints constraints;
}

