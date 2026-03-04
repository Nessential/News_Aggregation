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

import java.util.List;
import java.util.Map;

/**
 * Planner服务
 * 封装PlannerGraph调用与计划校验
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
     * 生成Plan
     *
     * @param request 计划请求
     * @return 计划结果
     */
    public ExecutionPlan plan(PlanRequest request) {
        try {
            // 配置关闭PlannerGraph时直接返回空计划
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
                log.warn("Plan validation failed, returning empty plan.");
                return emptyExecutionPlan(request);
            }

            return plan;
        } catch (Exception e) {
            log.error("PlannerService plan failed.", e);
            return emptyExecutionPlan(request);
        }
    }

    private ExecutionPlan emptyExecutionPlan(PlanRequest request) {
        return ExecutionPlan.builder()
                .planId("plan-empty")
                .goal(request != null ? request.getQuery() : "")
                .schemaVersion(request != null && request.getPlanSchema() != null
                        ? request.getPlanSchema() : plannerSchemaVersion)
                .semanticVersion(request != null && request.getSemanticVersion() != null
                        ? request.getSemanticVersion() : plannerSemanticVersion)
                .steps(List.of())
                .edges(List.of())
                .metadata(Map.of("taskCount", 0))
                .build();
    }
}
