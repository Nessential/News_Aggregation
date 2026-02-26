package com.example.news.aggregation.agent.client;

import com.example.news.aggregation.llm.springai.contract.RouterRequest;
import com.example.news.aggregation.llm.springai.contract.RouterResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * LLM Router 瀹㈡埛绔紙HTTP锛夈€? */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouterClient {

    private final RestTemplate restTemplate;

    @Value("${app.llm.router.base-url:http://localhost:8081}")
    private String routerBaseUrl;

    /**
     * 璋冪敤 LLM Router 璺敱鎺ュ彛銆?     */
    public RouterResult route(String sessionId, String query, List<String> history, Map<String, Object> constraints) {
        String url = routerBaseUrl + "/api/router/route";
        RouterRequest request = RouterRequest.builder()
                .sessionId(sessionId)
                .query(query)
                .history(history)
                .constraints(constraints)
                .build();
        try {
            log.info("[client] 璋冪敤璺敱鏈嶅姟FLOW|agent|client=router|step=start|sessionId={}|url={}|next=LLM-Router",
                    sessionId, url);
            ResponseEntity<RouterResult> response = restTemplate.postForEntity(
                    url, request, RouterResult.class);
            RouterResult body = response.getBody();
            if (body != null) {
                log.info("[client] 路由返回FLOW|agent|client=router|step=end|sessionId={}|taskFamily={}|retrievalMode={}|next=FSM决策", sessionId, body.getTaskFamily(), body.getRetrievalMode());

            }
            return body;
        } catch (Exception e) {
            log.warn("RouterClient route failed, fallback to default rule. error={}", e.getMessage());
            return null;
        }
    }
}

