package com.example.news.aggregation.agent.client;

import com.example.news.aggregation.llm.springai.contract.Plan;
import com.example.news.aggregation.llm.springai.contract.PlanRequest;
import com.example.news.aggregation.llm.springai.contract.RouterResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Planner服务客户端（HTTP）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerClient {

    private final RestTemplate restTemplate;

    @Value("${app.llm.base-url:http://localhost:8081}")
    private String llmBaseUrl;

    /**
     * 调用Planner生成Plan
     */
    public Plan plan(String query, RouterResult routerResult) {
        String url = llmBaseUrl + "/api/graph/plan";
        PlanRequest request = PlanRequest.builder()
                .query(query)
                .routerResult(routerResult)
                .build();
        try {
            ResponseEntity<Plan> response = restTemplate.postForEntity(url, request, Plan.class);
            return response.getBody();
        } catch (Exception e) {
            log.warn("PlannerClient plan failed, error={}", e.getMessage());
            return null;
        }
    }
}
