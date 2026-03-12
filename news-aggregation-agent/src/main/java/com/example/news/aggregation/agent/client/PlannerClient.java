package com.example.news.aggregation.agent.client;

import com.example.news.aggregation.llm.springai.contract.ExecutionConstraints;
import com.example.news.aggregation.llm.springai.contract.ExecutionPlan;
import com.example.news.aggregation.llm.springai.contract.ExecutionStep;
import com.example.news.aggregation.llm.springai.contract.PlanRequest;
import com.example.news.aggregation.llm.springai.contract.RouterResult;
import com.example.news.aggregation.rpc.api.LlmPlannerRpcService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_DELAY_MS = 500L;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @DubboReference(check = false, timeout = 8000, retries = 0)
    private LlmPlannerRpcService llmPlannerRpcService;

    @Value("${app.llm.base-url:http://localhost:8081}")
    private String llmBaseUrl;

    @Value("${app.agent.execution.schema-version:execution-plan/1.0}")
    private String executionSchemaVersion;

    @Value("${app.agent.execution.semantic-version:1.0.0}")
    private String executionSemanticVersion;

    @Value("${app.rpc.enabled:false}")
    private boolean rpcEnabled;

    public ExecutionPlan plan(String query, RouterResult routerResult) {
        return plan(query, routerResult, null);
    }

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

    public ExecutionPlan plan(PlanRequest request) {
        if (rpcEnabled) {
            long rpcStartNs = System.nanoTime();
            try {
                com.example.news.aggregation.rpc.contract.PlanRequest rpcRequest =
                        objectMapper.convertValue(request, com.example.news.aggregation.rpc.contract.PlanRequest.class);
                com.example.news.aggregation.rpc.contract.ExecutionPlan rpcPlan = llmPlannerRpcService.plan(rpcRequest);
                logLlmElapsed("RPC", request, rpcStartNs, true, 1);
                ExecutionPlan plan = objectMapper.convertValue(rpcPlan, ExecutionPlan.class);
                if (plan != null) {
                    return plan;
                }
            } catch (Exception e) {
                logLlmElapsed("RPC", request, rpcStartNs, false, 1);
                log.warn("[planner] rpc failed, fallback to http. error={}", e.getMessage());
            }
        }

        String url = llmBaseUrl + "/api/graph/plan";
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long httpStartNs = System.nanoTime();
            try {
                ResponseEntity<ExecutionPlan> response =
                        restTemplate.postForEntity(url, request, ExecutionPlan.class);
                logLlmElapsed("HTTP", request, httpStartNs, true, attempt);
                return response.getBody();
            } catch (Exception e) {
                logLlmElapsed("HTTP", request, httpStartNs, false, attempt);
                lastException = e;
                if (attempt < MAX_ATTEMPTS) {
                    long delay = INITIAL_DELAY_MS * (1L << (attempt - 1));
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.warn("[planner] all retries failed, fallback plan. error={}",
                lastException == null ? "unknown" : lastException.getMessage());
        return buildFallbackPlan(request);
    }

    private void logLlmElapsed(String channel, PlanRequest request, long startNs, boolean success, int attempt) {
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
        LlmMetricsContext.CallIndex idx = LlmMetricsContext.recordPlan(elapsedMs);
        String query = request == null ? null : request.getQuery();
        String schema = request == null ? null : request.getPlanSchema();
        log.info("大模型调用耗时|callNo={} |phaseCallNo={} |phase=计划生成 |channel={} |attempt={} |schema={} |query={} |elapsedSec={} |success={}",
                idx.callNo(),
                idx.phaseCallNo(),
                channel,
                attempt,
                schema,
                querySummary(query),
                formatSeconds(elapsedMs),
                success);
        log.info("[llm-timing] phase=plan |channel={} |attempt={} |schema={} |query={} |elapsedSec={} |success={}",
                channel,
                attempt,
                schema,
                querySummary(query),
                formatSeconds(elapsedMs),
                success);
    }

    private String querySummary(String query) {
        if (query == null) {
            return "null";
        }
        String compact = query.replaceAll("\\s+", " ").trim();
        return "len=" + compact.length() + ",value=" + truncate(compact, 120);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String formatSeconds(long elapsedMs) {
        return String.format("%.3f", elapsedMs / 1000.0);
    }

    private ExecutionPlan buildFallbackPlan(PlanRequest request) {
        String planId = "plan-fallback-" + UUID.randomUUID().toString().substring(0, 8);
        String query = request != null ? request.getQuery() : "";

        ExecutionStep searchStep = ExecutionStep.builder()
                .stepId("fallback-step-1")
                .tool("search_news")
                .name("keyword search fallback")
                .input(Map.of("query", query))
                .build();

        ExecutionStep generateStep = ExecutionStep.builder()
                .stepId("fallback-step-2")
                .tool("llm_generate")
                .name("generate fallback")
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

