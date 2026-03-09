package com.example.news.aggregation.rpc.contract;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 
 * ?Router  PlannerGraph  Plan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /**  */
    private String query;

    /** Router  */
    private RouterResult routerResult;

    /**  */
    private Map<String, Object> context;

    /**  */
    private String planSchema;

    /**  */
    private String semanticVersion;

    /** ?Replan rue ?TaskDecompositionNode ?*/
    private Boolean isReplan;

    /**  Replan  LLM prompt?*/
    private String replanReason;

    /** Step execution summaries for replan prompt. */
    private Map<String, StepExecutionResult> stepResults;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepExecutionResult implements Serializable {
        private static final long serialVersionUID = 1L;
        private String stepId;
        private String status;
        private String toolUsed;
        private String outputSummary;
        private String failureReason;
        private int evidenceCount;
    }
}
