package com.example.news.aggregation.agent.client;

import com.example.news.aggregation.llm.springai.contract.GeneratorDraft;
import com.example.news.aggregation.llm.springai.contract.GeneratorRequest;
import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import com.example.news.aggregation.rpc.api.LlmGeneratorRpcService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeneratorClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @DubboReference(check = false, timeout = 10000, retries = 0)
    private LlmGeneratorRpcService llmGeneratorRpcService;

    @Value("${app.llm.base-url:http://localhost:8081}")
    private String llmBaseUrl;

    @Value("${app.rpc.enabled:false}")
    private boolean rpcEnabled;

    public GeneratorDraft generate(String query, String queryInterpretation, String taskFamily, List<RetrievalResult> evidence, String retrievalMode) {
        GeneratorRequest request = GeneratorRequest.builder()
                .query(query)
                .queryInterpretation(queryInterpretation)
                .taskFamily(taskFamily)
                .retrievalMode(retrievalMode)
                .evidence(evidence)
                .build();

        if (rpcEnabled) {
            long startNs = System.nanoTime();
            try {
                com.example.news.aggregation.rpc.contract.GeneratorRequest rpcRequest =
                        objectMapper.convertValue(request, com.example.news.aggregation.rpc.contract.GeneratorRequest.class);
                com.example.news.aggregation.rpc.contract.GeneratorDraft rpcDraft =
                        llmGeneratorRpcService.generate(rpcRequest);
                logLlmElapsed("RPC", query, taskFamily, retrievalMode, startNs, true);
                return objectMapper.convertValue(rpcDraft, GeneratorDraft.class);
            } catch (Exception e) {
                logLlmElapsed("RPC", query, taskFamily, retrievalMode, startNs, false);
                log.warn("GeneratorClient rpc failed, fallback to http. error={}", e.getMessage());
            }
        }

        String url = llmBaseUrl + "/api/graph/generate";
        long startNs = System.nanoTime();
        try {
            ResponseEntity<GeneratorDraft> response = restTemplate.postForEntity(url, request, GeneratorDraft.class);
            logLlmElapsed("HTTP", query, taskFamily, retrievalMode, startNs, true);
            return response.getBody();
        } catch (Exception e) {
            logLlmElapsed("HTTP", query, taskFamily, retrievalMode, startNs, false);
            log.warn("GeneratorClient generate failed, error={}", e.getMessage());
            return null;
        }
    }

    public GeneratorDraft generate(String query, String taskFamily, List<RetrievalResult> evidence) {
        return generate(query, null, taskFamily, evidence, null);
    }

    private void logLlmElapsed(String channel,
                               String query,
                               String taskFamily,
                               String retrievalMode,
                               long startNs,
                               boolean success) {
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
        LlmMetricsContext.CallIndex idx = LlmMetricsContext.recordAnswer(elapsedMs);
        log.info("大模型调用耗时|callNo={} |phaseCallNo={} |phase=答案生成 |channel={} |taskFamily={} |retrievalMode={} |query={} |elapsedSec={} |success={}",
                idx.callNo(),
                idx.phaseCallNo(),
                channel,
                taskFamily,
                retrievalMode,
                querySummary(query),
                formatSeconds(elapsedMs),
                success);
        log.info("[llm-timing] phase=answer |channel={} |taskFamily={} |retrievalMode={} |query={} |elapsedSec={} |success={}",
                channel,
                taskFamily,
                retrievalMode,
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
