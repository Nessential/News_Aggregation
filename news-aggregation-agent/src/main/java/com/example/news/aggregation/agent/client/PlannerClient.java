package com.example.news.aggregation.agent.client;

import com.example.news.aggregation.llm.springai.contract.ExecutionConstraints;
import com.example.news.aggregation.llm.springai.contract.ExecutionPlan;
import com.example.news.aggregation.llm.springai.contract.ExecutionStep;
import com.example.news.aggregation.llm.springai.contract.PlanRequest;
import com.example.news.aggregation.llm.springai.contract.RouterResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Planner HTTP 客户端。
 * 支持 3 次指数退避重试，全部失败时降级返回保底两步计划（search_news → llm_generate）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_DELAY_MS = 500L;

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
     */
    public ExecutionPlan plan(String query, RouterResult routerResult, Map<String, Object> context) {
        PlanRequest request = PlanRequest.builder()
                .query(query)
                .routerResult(routerResult)
                .context(context)
                .planSchema(executionSchemaVersion)
                .semanticVersion(executionSemanticVersion)
                .build();
        return plan(request);
    }

    /**
     * 调用 Planner 生成计划（支持完整 PlanRequest，含 Replan 上下文）。
     * 最多重试 3 次（指数退避），全部失败后降级返回保底计划。
     */
    public ExecutionPlan plan(PlanRequest request) {
        String url = llmBaseUrl + "/api/graph/plan";
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                log.info("[客户端][规划] 调用规划服务|attempt={}/{}|isReplan={}|url={}",
                        attempt, MAX_ATTEMPTS,
                        Boolean.TRUE.equals(request.getIsReplan()),
                        url);

                ResponseEntity<ExecutionPlan> response =
                        restTemplate.postForEntity(url, request, ExecutionPlan.class);
                ExecutionPlan body = response.getBody();
                int taskCount = body != null && body.getSteps() != null ? body.getSteps().size() : 0;

                log.info("[客户端][规划] 规划服务返回成功|attempt={}|taskCount={}|planId={}",
                        attempt, taskCount, body == null ? null : body.getPlanId());
                return body;

            } catch (Exception e) {
                lastException = e;
                log.warn("[客户端][规划] 第 {} 次调用失败|error={}", attempt, e.getMessage());

                if (attempt < MAX_ATTEMPTS) {
                    long delay = INITIAL_DELAY_MS * (1L << (attempt - 1)); // 500ms, 1000ms
                    log.info("[客户端][规划] {}ms 后重试...", delay);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.warn("[客户端][规划] {} 次重试全部失败，降级到保底计划|query={}|error={}",
                MAX_ATTEMPTS, request.getQuery(),
                lastException == null ? "unknown" : lastException.getMessage());
        return buildFallbackPlan(request);
    }

    /**
     * 保底计划：search_news → llm_generate 两步。
     * 保证 Planner 不可用时用户仍能拿到基本答案。
     */
    private ExecutionPlan buildFallbackPlan(PlanRequest request) {
        String planId = "plan-fallback-" + UUID.randomUUID().toString().substring(0, 8);
        String query = request != null ? request.getQuery() : "";

        ExecutionStep searchStep = ExecutionStep.builder()
                .stepId("fallback-step-1")
                .tool("search_news")
                .name("关键词检索（保底计划）")
                .input(Map.of("query", query))
                .build();

        ExecutionStep generateStep = ExecutionStep.builder()
                .stepId("fallback-step-2")
                .tool("llm_generate")
                .name("生成答案（保底计划）")
                .dependsOn(List.of("fallback-step-1"))
                .input(Map.of("query", query))
                .build();

        return ExecutionPlan.builder()
                .planId(planId)
                .goal(query)
                .schemaVersion(executionSchemaVersion)
                .semanticVersion(executionSemanticVersion)
                .steps(List.of(searchStep, generateStep))
                .edges(List.of())
                .constraints(ExecutionConstraints.builder()
                        .maxSteps(5)
                        .maxToolCalls(10)
                        .timeoutMs(60000L)
                        .build())
                .metadata(Map.of(
                        "plannerMode", "FALLBACK",
                        "fallbackReason", "planner_unavailable",
                        "taskCount", 2
                ))
                .build();
    }
}
