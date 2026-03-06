package com.example.news.aggregation.agent.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 回放接口响应体。
 */
@Data
@Builder
public class ExecutionReplayResponse {

    private RunView run;
    private List<StepRunView> stepRuns;
    private List<EventView> events;
    private SummaryView summary;

    @Data
    @Builder
    public static class RunView {
        private String runId;
        private String tenantId;
        private String sessionId;
        private String turnId;
        private String status;
        private String currentStep;
        private String errorCode;
        private String errorMessage;
        private Integer activePlanVersion;
        private Integer replanCountRun;
        private Date startedAt;
        private Date finishedAt;
    }

    @Data
    @Builder
    public static class StepRunView {
        private Long id;
        private String stepId;
        private Integer planVersion;
        private String capabilityName;
        private String activeCapabilityName;
        private String status;
        private Integer attempt;
        private Integer maxRetries;
        private Integer recoveryAttempt;
        private Integer maxRecoveryAttempts;
        private String selectedTool;
        private String selectionReasonCode;
        private Integer replanCountStep;
        private String lastReplanReasonCode;
        private String replanDecisionAction;
        private String reasonCode;
        private String errorCode;
        private String errorMessage;
        private Date startedAt;
        private Date finishedAt;
    }

    @Data
    @Builder
    public static class EventView {
        private Long id;
        private String stepId;
        private String eventType;
        private Integer eventVersion;
        private String fromState;
        private String toState;
        private String reasonCode;
        private String message;
        private String payloadJson;
        private Date gmtCreate;
    }

    @Data
    @Builder
    public static class SummaryView {
        private Integer activePlanVersion;
        private Integer stepCount;
        private Long eventCount;
        private String terminalState;
        private String timelineDigest;
        private String plannerTraceId;
    }
}
