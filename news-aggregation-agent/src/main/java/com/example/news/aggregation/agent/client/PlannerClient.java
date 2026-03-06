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

import java.util.Map;

/**
 * Planner HTTP 客户端。
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
     * 调用 Planner 生成计划（兼容旧调用）。
     */
    public ExecutionPlan plan(String query, RouterResult routerResult) {
        return plan(query, routerResult, null);
    }

    /**
     * 调用 Planner 生成计划。
     *
     * @param query 用户问题
     * @param routerResult 路由结果
     * @param context 规划上下文（可选），用于透传 planner 模式、trace 等调试信息
     * @return 结构化执行计划
     */
    public ExecutionPlan plan(String query, RouterResult routerResult, Map<String, Object> context) {
        String url = llmBaseUrl + "/api/graph/plan";
        PlanRequest request = PlanRequest.builder()
                .query(query)
                .routerResult(routerResult)
                .context(context)
                .planSchema(executionSchemaVersion)
                .semanticVersion(executionSemanticVersion)
                .build();
        try {
            log.info("[客户端][规划] 开始调用规划服务|url={} |hasContext={}", url, context != null && !context.isEmpty());
            ResponseEntity<ExecutionPlan> response = restTemplate.postForEntity(url, request, ExecutionPlan.class);
            ExecutionPlan body = response.getBody();
            int taskCount = body != null && body.getSteps() != null ? body.getSteps().size() : 0;
            log.info("[客户端][规划] 规划服务返回成功|taskCount={} |planId={}",
                    taskCount, body == null ? null : body.getPlanId());
            return body;
        } catch (Exception e) {
            log.warn("[客户端][规划] 调用失败|error={}", e.getMessage());
            return null;
        }
    }
}
