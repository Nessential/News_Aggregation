package com.example.news.aggregation.llm.springai.rpc.provider;

import com.example.news.aggregation.llm.springai.contract.ExecutionPlan;
import com.example.news.aggregation.llm.springai.contract.PlanRequest;
import com.example.news.aggregation.llm.springai.service.PlannerService;
import com.example.news.aggregation.rpc.api.LlmPlannerRpcService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;

@Slf4j
@DubboService
@RequiredArgsConstructor
public class LlmPlannerRpcServiceImpl implements LlmPlannerRpcService {

    private final PlannerService plannerService;
    private final ObjectMapper objectMapper;

    @Override
    public com.example.news.aggregation.rpc.contract.ExecutionPlan plan(
            com.example.news.aggregation.rpc.contract.PlanRequest request) {
        try {
            PlanRequest planRequest = objectMapper.convertValue(request, PlanRequest.class);
            ExecutionPlan result = plannerService.plan(planRequest);
            return objectMapper.convertValue(result, com.example.news.aggregation.rpc.contract.ExecutionPlan.class);
        } catch (Exception e) {
            log.error("RPC plan failed", e);
            return com.example.news.aggregation.rpc.contract.ExecutionPlan.builder()
                    .planId("rpc-empty")
                    .goal(request != null ? request.getQuery() : "")
                    .steps(java.util.List.of())
                    .edges(java.util.List.of())
                    .build();
        }
    }
}
