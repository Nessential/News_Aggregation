package com.example.news.aggregation.agent.client;

import com.example.news.aggregation.llm.springai.contract.RouterRequest;
import com.example.news.aggregation.llm.springai.contract.RouterResult;
import com.example.news.aggregation.rpc.api.LlmRouterRpcService;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class RouterClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @DubboReference(check = false, timeout = 5000, retries = 0)
    private LlmRouterRpcService llmRouterRpcService;

    @Value("${app.llm.router.base-url:http://localhost:8081}")
    private String routerBaseUrl;

    @Value("${app.rpc.enabled:false}")
    private boolean rpcEnabled;

    public RouterResult route(String sessionId, String query, List<String> history, Map<String, Object> constraints) {
        RouterRequest request = RouterRequest.builder()
                .sessionId(sessionId)
                .query(query)
                .history(history)
                .constraints(constraints)
                .build();

        if (rpcEnabled) {
            try {
                com.example.news.aggregation.rpc.contract.RouterRequest rpcRequest =
                        objectMapper.convertValue(request, com.example.news.aggregation.rpc.contract.RouterRequest.class);
                com.example.news.aggregation.rpc.contract.RouterResult rpcResult = llmRouterRpcService.route(rpcRequest);
                RouterResult body = objectMapper.convertValue(rpcResult, RouterResult.class);
                if (body != null) {
                    log.info("[client] rpc route done|sessionId={}|taskFamily={}|retrievalMode={}",
                            sessionId, body.getTaskFamily(), body.getRetrievalMode());
                }
                return body;
            } catch (Exception e) {
                log.warn("RouterClient rpc failed, fallback to http. error={}", e.getMessage());
            }
        }

        String url = routerBaseUrl + "/api/router/route";
        try {
            log.info("[client] call router http|sessionId={}|url={}", sessionId, url);
            ResponseEntity<RouterResult> response = restTemplate.postForEntity(url, request, RouterResult.class);
            return response.getBody();
        } catch (Exception e) {
            log.warn("RouterClient route failed, fallback to default rule. error={}", e.getMessage());
            return null;
        }
    }
}

