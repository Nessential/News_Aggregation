package com.example.news.aggregation.agent.domain;

import com.example.news.aggregation.agent.enums.ConversationState;
import com.example.news.aggregation.agent.enums.TurnStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 单轮执行状态快照。
 * <p>
 * 用于记录一次问答(turn)从开始到结束的状态，避免跨轮 FSM 污染。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TurnState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 轮次ID */
    private String turnId;

    /** 会话ID */
    private String sessionId;

    /** 请求摘要，可用于排查重放问题 */
    private String requestHash;

    /** Turn 执行状态 */
    @Builder.Default
    private TurnStatus status = TurnStatus.PENDING;

    /** 当前轮 FSM 状态 */
    @Builder.Default
    private ConversationState fsmState = ConversationState.START;

    /** 本轮最终结果快照 */
    private AgentResponse resultSnapshot;

    /** 失败错误码 */
    private String errorCode;

    /** 失败错误信息 */
    private String errorMessage;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 完成时间 */
    private LocalDateTime finishedAt;
}

