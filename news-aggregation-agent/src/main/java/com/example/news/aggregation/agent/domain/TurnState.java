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
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TurnState implements Serializable {
    private static final long serialVersionUID = 1L;

    private String turnId;
    private String sessionId;
    private String requestHash;

    /** 当前turn绑定的runId。 */
    private String runId;

    @Builder.Default
    private TurnStatus status = TurnStatus.PENDING;

    @Builder.Default
    private ConversationState fsmState = ConversationState.START;

    private AgentResponse resultSnapshot;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;
}
