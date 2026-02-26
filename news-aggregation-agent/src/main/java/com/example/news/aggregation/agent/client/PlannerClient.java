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
 * Planner 瀹㈡埛绔紙HTTP锛夈€? */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerClient {

    private final RestTemplate restTemplate;

    @Value("${app.llm.base-url:http://localhost:8081}")
    private String llmBaseUrl;

    /**
     * 璋冪敤 Planner 鐢熸垚璁″垝銆?     */
    public Plan plan(String query, RouterResult routerResult) {
        String url = llmBaseUrl + "/api/graph/plan";
        PlanRequest request = PlanRequest.builder()
                .query(query)
                .routerResult(routerResult)
                .build();
        try {
            log.info("[client] 璋冪敤瑙勫垝鏈嶅姟FLOW|agent|client=planner|step=start|url={}|next=LLM-Planner", url);
            ResponseEntity<Plan> response = restTemplate.postForEntity(url, request, Plan.class);
            Plan body = response.getBody();
            int taskCount = body != null && body.getTasks() != null ? body.getTasks().size() : 0;
            log.info("[client] 瑙勫垝杩斿洖FLOW|agent|client=planner|step=end|taskCount={}|next=鎵ц璁″垝", taskCount);
            return body;
        } catch (Exception e) {
            log.warn("PlannerClient plan failed, error={}", e.getMessage());
            return null;
        }
    }
}

