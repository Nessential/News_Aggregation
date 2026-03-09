package com.example.news.aggregation.rpc.contract;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * ? */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionPlan implements Serializable {
    private static final long serialVersionUID = 1L;
    private String planId;
    /** eek5 ?*/
    private Integer planVersion;
    /** D?*/
    private String parentPlanId;
    /** ?*/
    private String replanReasonCode;
    /** Planner ID?Planner -> Executor -> Replay ?*/
    private String plannerTraceId;
    private String goal;
    private String schemaVersion;
    private String semanticVersion;
    private List<ExecutionStep> steps;
    private List<ExecutionEdge> edges;
    private ExecutionConstraints constraints;
    private Map<String, Object> metadata;
}
