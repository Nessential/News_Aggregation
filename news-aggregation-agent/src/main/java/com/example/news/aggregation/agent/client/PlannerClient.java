package com.example.news.aggregation.agent.client;

import com.example.news.aggregation.llm.springai.contract.ExecutionPlan;
import com.example.news.aggregation.llm.springai.contract.PlanRequest;
import com.example.news.aggregation.llm.springai.contract.RouterResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Planner 客户端 (HTTP)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerClient {

    private final RestTemplate restTemplate;

    @Value("${app.llm.base-url:http://localhost:8081}")
    private String llmBaseUrl;

    @Value("${app.agent.execution.schema-version:execution-plan/1.0}")
    private String executionSchemaVersion;

    @Value("${app.agent.execution.semantic-version:1.0.0}")
    private String executionSemanticVersion;

    /**
     * 调用 Planner 生成计划。
     */
    public ExecutionPlan plan(String query, RouterResult routerResult) {
        String url = llmBaseUrl + "/api/graph/plan";
        PlanRequest request = PlanRequest.builder()
                .query(query)
                .routerResult(routerResult)
                .planSchema(executionSchemaVersion)
                .semanticVersion(executionSemanticVersion)
                .build();
        try {
            log.info("[client] 调用规划服务FLOW|agent|client=planner|step=start|url={}|next=LLM-Planner", url);
            ResponseEntity<ExecutionPlan> response = restTemplate.postForEntity(url, request, ExecutionPlan.class);
            ExecutionPlan body = response.getBody();
            int taskCount = body != null && body.getSteps() != null ? body.getSteps().size() : 0;
            log.info("[client] 规划返回FLOW|agent|client=planner|step=end|taskCount={}|next=执行计划", taskCount);
            return body;
        } catch (Exception e) {
            log.warn("PlannerClient plan failed, error={}", e.getMessage());
            return null;
        }
    }
}
