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
            long rpcStartNs = System.nanoTime();
            try {
                com.example.news.aggregation.rpc.contract.RouterRequest rpcRequest =
                        objectMapper.convertValue(request, com.example.news.aggregation.rpc.contract.RouterRequest.class);
                com.example.news.aggregation.rpc.contract.RouterResult rpcResult = llmRouterRpcService.route(rpcRequest);
                RouterResult body = objectMapper.convertValue(rpcResult, RouterResult.class);
                logLlmElapsed("RPC", sessionId, query, rpcStartNs, true);
                if (body != null) {
                    log.info("[client] rpc route done|sessionId={}|taskFamily={}|retrievalMode={}",
                            sessionId, body.getTaskFamily(), body.getRetrievalMode());
                }
                return body;
            } catch (Exception e) {
                logLlmElapsed("RPC", sessionId, query, rpcStartNs, false);
                log.warn("RouterClient rpc failed, fallback to http. error={}", e.getMessage());
            }
        }

        String url = routerBaseUrl + "/api/router/route";
        long httpStartNs = System.nanoTime();
        try {
            log.info("[client] call router http|sessionId={}|url={}", sessionId, url);
            ResponseEntity<RouterResult> response = restTemplate.postForEntity(url, request, RouterResult.class);
            logLlmElapsed("HTTP", sessionId, query, httpStartNs, true);
            return response.getBody();
        } catch (Exception e) {
            logLlmElapsed("HTTP", sessionId, query, httpStartNs, false);
            log.warn("RouterClient route failed, fallback to default rule. error={}", e.getMessage());
            return null;
        }
    }

    private void logLlmElapsed(String channel, String sessionId, String query, long startNs, boolean success) {
        long elapsedMs = startNs > 0 ? (System.nanoTime() - startNs) / 1_000_000 : 0L;
        LlmMetricsContext.CallIndex idx = LlmMetricsContext.recordIntent(elapsedMs);
        log.info("大模型调用耗时|callNo={} |phaseCallNo={} |phase=意图识别 |channel={} |sessionId={} |query={} |elapsedSec={} |success={}",
                idx.callNo(),
                idx.phaseCallNo(),
                channel,
                sessionId,
                querySummary(query),
                formatSeconds(elapsedMs),
                success);
        log.info("[llm-timing] phase=intent |channel={} |sessionId={} |query={} |elapsedSec={} |success={}",
                channel,
                sessionId,
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
}

