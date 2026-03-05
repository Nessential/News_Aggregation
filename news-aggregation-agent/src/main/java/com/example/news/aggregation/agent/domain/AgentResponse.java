package com.example.news.aggregation.agent.domain;

import com.example.news.aggregation.agent.enums.TaskFamily;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 统一响应体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    private String sessionId;
    private String turnId;
    private String turnStatus;
    private String errorCode;
    private String runningTurnId;

    private String answer;
    private List<Candidate> candidates;
    private List<String> citations;
    private TaskFamily taskFamily;
    private Boolean needsClarification;
    private String clarificationPrompt;
    private LocalDateTime timestamp;
    private Long executionTimeMs;
    private ResponseMetadata metadata;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponseMetadata implements Serializable {
        private static final long serialVersionUID = 1L;

        private Integer retrievedCount;
        private Integer llmCallCount;
        private String pipelineType;
        private Integer remainingBudget;

        private Boolean qualityGateTriggered;
        private Integer qualityWarningCount;
        private List<String> qualityWarnings;
        private String schemaValidationMode;
        private String executionSchemaVersion;
        private String executionSemanticVersion;
        private Integer inputValidationCount;
        private Integer outputValidationCount;
        private Boolean degradeOutputTriggered;
        private String degradeReasonCode;
        private String degradeStepId;

        /** 第三周新增：持久化执行观测字段。 */
        private String executionRunId;
        private String executionRunStatus;
        private String currentExecutionStep;
        private String effectLatchStatus;
        private Integer executionStepAttempt;
        private String executionReasonCode;
    }
}
