package com.example.news.aggregation.llm.springai.service;

import com.example.news.aggregation.llm.springai.config.GraphProperties;
import com.example.news.aggregation.llm.springai.contract.Plan;
import com.example.news.aggregation.llm.springai.contract.PlanRequest;
import com.example.news.aggregation.llm.springai.graph.PlannerGraph;
import com.example.news.aggregation.llm.springai.state.PlannerState;
import com.example.news.aggregation.llm.springai.validator.OutputValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

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

    /**
     * 生成Plan
     *
     * @param request 计划请求
     * @return 计划结果
     */
    public Plan plan(PlanRequest request) {
        try {
            // 配置关闭PlannerGraph时直接返回空计划
            if (!graphProperties.isPlannerEnabled()) {
                return Plan.builder().tasks(List.of()).build();
            }

            PlannerState state = PlannerState.builder()
                    .query(request.getQuery())
                    .routerResult(request.getRouterResult())
                    .context(request.getContext())
                    .build();

            PlannerState finalState = plannerGraph.invoke(state);
            Plan plan = finalState.getPlan();

            if (!validator.validatePlan(plan)) {
                log.warn("Plan validation failed, returning empty plan.");
                return Plan.builder().tasks(List.of()).build();
            }

            return plan;
        } catch (Exception e) {
            log.error("PlannerService plan failed.", e);
            return Plan.builder().tasks(List.of()).build();
        }
    }
}