package com.example.news.aggregation.llm.springai.service;

import com.example.news.aggregation.llm.springai.config.GraphProperties;
import com.example.news.aggregation.llm.springai.contract.ExecutionPlan;
import com.example.news.aggregation.llm.springai.contract.PlanRequest;
import com.example.news.aggregation.llm.springai.graph.PlannerGraph;
import com.example.news.aggregation.llm.springai.state.PlannerState;
import com.example.news.aggregation.llm.springai.validator.OutputValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 规划服务。
 * <p>
 * 服务统一调用 PlannerGraph 生成执行计划，并补齐规划链路元数据。
 * 当前只保留基于工具声明的规划主链路，不再保留旧的手写工具说明规划模式。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlannerService {

    private final PlannerGraph plannerGraph;
    private final OutputValidator validator;
    private final GraphProperties graphProperties;

    @Value("${app.llm.planner.schema-version:execution-plan/1.0}")
    private String plannerSchemaVersion;

    @Value("${app.llm.planner.semantic-version:1.0.0}")
    private String plannerSemanticVersion;

    public ExecutionPlan plan(PlanRequest request) {
        try {
            if (!graphProperties.isPlannerEnabled()) {
                log.warn("[规划服务] 规划图未启用，返回空计划。");
                return emptyExecutionPlan(request);
            }

            PlannerState state = PlannerState.builder()
                    .query(request.getQuery())
                    .routerResult(request.getRouterResult())
                    .context(request.getContext())
                    .semanticVersion(request.getSemanticVersion() != null
                            ? request.getSemanticVersion() : plannerSemanticVersion)
                    .isReplan(Boolean.TRUE.equals(request.getIsReplan()))
                    .replanReason(request.getReplanReason())
                    .stepResults(request.getStepResults())
                    .build();

            log.info("[规划服务] 开始生成执行计划。query={}，isReplan={}",
                    truncate(request.getQuery(), 120), Boolean.TRUE.equals(request.getIsReplan()));

            PlannerState finalState = plannerGraph.invoke(state);
            ExecutionPlan plan = finalState.getExecutionPlan();

            if (!validator.validateExecutionPlan(plan)) {
                log.warn("[规划服务] 执行计划结构校验失败，返回空计划。query={}", truncate(request.getQuery(), 120));
                return emptyExecutionPlan(request);
            }

            ExecutionPlan enrichedPlan = enrichPlanMetadata(plan, request);
            log.info("[规划服务] 执行计划生成完成。planId={}，stepCount={}",
                    enrichedPlan.getPlanId(),
                    enrichedPlan.getSteps() == null ? 0 : enrichedPlan.getSteps().size());
            return enrichedPlan;
        } catch (Exception e) {
            log.error("[规划服务] 执行计划生成失败，返回空计划。query={}，error={}",
                    request == null ? "" : truncate(request.getQuery(), 120),
                    e.getMessage(), e);
            return emptyExecutionPlan(request);
        }
    }

    private ExecutionPlan emptyExecutionPlan(PlanRequest request) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("taskCount", 0);
        metadata.put("plannerTraceId", resolvePlannerTraceId(request));
        metadata.put("plannerMode", resolvePlannerMode(request));
        metadata.put("toolBindingMode", "spring_tool");
        metadata.put("planningChannel", "spring_ai_tool");

        return ExecutionPlan.builder()
                .planId("plan-empty")
                .goal(request != null ? request.getQuery() : "")
                .schemaVersion(request != null && request.getPlanSchema() != null
                        ? request.getPlanSchema() : plannerSchemaVersion)
                .semanticVersion(request != null && request.getSemanticVersion() != null
                        ? request.getSemanticVersion() : plannerSemanticVersion)
                .steps(List.of())
                .edges(List.of())
                .metadata(metadata)
                .build();
    }

    private ExecutionPlan enrichPlanMetadata(ExecutionPlan plan, PlanRequest request) {
        if (plan == null) {
            return emptyExecutionPlan(request);
        }

        Map<String, Object> metadata = new HashMap<>();
        if (plan.getMetadata() != null) {
            metadata.putAll(plan.getMetadata());
        }

        String plannerTraceId = resolvePlannerTraceId(request);
        String plannerMode = resolvePlannerMode(request);
        metadata.put("plannerTraceId", plannerTraceId);
        metadata.put("plannerMode", plannerMode);
        metadata.put("toolBindingMode", "spring_tool");
        metadata.put("planningChannel", "spring_ai_tool");
        metadata.putIfAbsent("taskCount", plan.getSteps() == null ? 0 : plan.getSteps().size());

        plan.setMetadata(metadata);

        log.info("[规划服务] 已补齐计划元数据。planId={}，plannerTraceId={}，plannerMode={}，stepCount={}",
                plan.getPlanId(),
                plannerTraceId,
                plannerMode,
                plan.getSteps() == null ? 0 : plan.getSteps().size());
        return plan;
    }

    private String resolvePlannerTraceId(PlanRequest request) {
        if (request != null && request.getContext() != null) {
            Object trace = request.getContext().get("plannerTraceId");
            if (trace != null) {
                String candidate = String.valueOf(trace).trim();
                if (!candidate.isBlank()) {
                    return candidate;
                }
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String resolvePlannerMode(PlanRequest request) {
        if (request != null && request.getContext() != null) {
            Object mode = request.getContext().get("plannerMode");
            if (mode != null) {
                String candidate = String.valueOf(mode).trim();
                if (!candidate.isBlank()) {
                    return candidate;
                }
            }
        }
        return "HYBRID";
    }

    private String truncate(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
