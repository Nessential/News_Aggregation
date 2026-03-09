package com.example.news.aggregation.rpc.api;

import com.example.news.aggregation.rpc.contract.ExecutionPlan;
import com.example.news.aggregation.rpc.contract.PlanRequest;

public interface LlmPlannerRpcService {
    ExecutionPlan plan(PlanRequest request);
}
