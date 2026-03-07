package com.example.news.aggregation.agent.domain;

import com.example.news.aggregation.agent.enums.TaskFamily;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

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

    /** Full merged text for logging/history. */
    private String answer;

    /** New structured answer for frontend rendering. */
    private List<AnswerItemView> answerItems;

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
    public static class AnswerItemView implements Serializable {
        private static final long serialVersionUID = 1L;

        private String text;
        private List<Long> newsIds;
        private List<Candidate> relatedNews;
    }

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

        private String executionRunId;
        private String executionRunStatus;
        private String currentExecutionStep;
        private String effectLatchStatus;
        private Integer executionStepAttempt;
        private String executionReasonCode;
    }
}
