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
 * Planner 服务：封装 PlannerGraph 调用、计划校验与元数据回填。
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

    /**
     * 生成执行计划。
     */
    public ExecutionPlan plan(PlanRequest request) {
        try {
            if (!graphProperties.isPlannerEnabled()) {
                return emptyExecutionPlan(request);
            }

            PlannerState state = PlannerState.builder()
                    .query(request.getQuery())
                    .routerResult(request.getRouterResult())
                    .context(request.getContext())
                    .semanticVersion(request.getSemanticVersion() != null
                            ? request.getSemanticVersion() : plannerSemanticVersion)
                    .build();

            PlannerState finalState = plannerGraph.invoke(state);
            ExecutionPlan plan = finalState.getExecutionPlan();

            if (!validator.validateExecutionPlan(plan)) {
                log.warn("[规划服务] 计划结构校验失败，返回空计划兜底。");
                return emptyExecutionPlan(request);
            }

            return enrichPlanMetadata(plan, request);
        } catch (Exception e) {
            log.error("[规划服务] 计划生成异常，返回空计划兜底。", e);
            return emptyExecutionPlan(request);
        }
    }

    private ExecutionPlan emptyExecutionPlan(PlanRequest request) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("taskCount", 0);
        metadata.put("plannerTraceId", resolvePlannerTraceId(request));
        metadata.put("plannerMode", resolvePlannerMode(request));
        metadata.put("toolBindingMode", resolveToolBindingMode(request));

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

    /**
     * 统一回填 planner 元数据，保证 trace、模式、工具绑定信息不丢失。
     */
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
        String toolBindingMode = resolveToolBindingMode(request);
        metadata.put("plannerTraceId", plannerTraceId);
        metadata.put("plannerMode", plannerMode);
        metadata.put("toolBindingMode", toolBindingMode);
        metadata.putIfAbsent("taskCount", plan.getSteps() == null ? 0 : plan.getSteps().size());

        plan.setMetadata(metadata);

        log.info("[规划服务] 计划元数据补齐完成|planId={} |plannerTraceId={} |plannerMode={} |toolBindingMode={} |stepCount= {}",
                plan.getPlanId(),
                plannerTraceId,
                plannerMode,
                toolBindingMode,
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

    private String resolveToolBindingMode(PlanRequest request) {
        if (request != null && request.getContext() != null) {
            Object mode = request.getContext().get("toolBindingMode");
            if (mode != null) {
                String candidate = String.valueOf(mode).trim().toLowerCase();
                if (!candidate.isBlank()) {
                    return candidate;
                }
            }
        }
        return "legacy";
    }
}
