package com.example.news.aggregation.agent.domain;

import com.example.news.aggregation.agent.enums.IdempotencyStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 幂等记录。
 * <p>
 * 使用幂等键作为 Redis key，value 记录状态和结果快照。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class IdempotencyRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 幂等键 */
    private String idempotencyKey;

    /** 会话ID */
    private String sessionId;

    /** 轮次ID */
    private String turnId;

    /** 请求摘要，用于排查误复用 */
    private String requestHash;

    /** 幂等状态 */
    private IdempotencyStatus status;

    /** 错误码 */
    private String errorCode;

    /** 错误信息 */
    private String errorMessage;

    /** 最终响应快照 */
    private AgentResponse responseSnapshot;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}

