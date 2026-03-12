package com.example.news.aggregation.agent.client;

import com.example.news.aggregation.agent.tool.dto.RetrievalResult;
import com.example.news.aggregation.rpc.api.NewsRetrievalRpcService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @DubboReference(check = false, timeout = 8000, retries = 0)
    private NewsRetrievalRpcService newsRetrievalRpcService;

    @Value("${app.news.retrieval.base-url:http://localhost:8082}")
    private String retrievalBaseUrl;

    @Value("${app.rpc.enabled:false}")
    private boolean rpcEnabled;

    public List<RetrievalResult> keywordSearch(String query, int topK) {
        return post("/api/news/retrieval/keyword", query, topK, null, null);
    }

    public List<RetrievalResult> keywordSearch(String query, int topK, Map<String, Object> filters) {
        return post("/api/news/retrieval/keyword", query, topK, null, filters);
    }

    public List<RetrievalResult> vectorSearch(String query, int topK, double minScore) {
        return post("/api/news/retrieval/vector", query, topK, minScore, null);
    }

    public List<RetrievalResult> vectorSearch(String query, int topK, double minScore, Map<String, Object> filters) {
        return post("/api/news/retrieval/vector", query, topK, minScore, filters);
    }

    public List<RetrievalResult> hybridSearch(String query, int topK, double minScore) {
        return post("/api/news/retrieval/hybrid", query, topK, minScore, null);
    }

    public List<RetrievalResult> hybridSearch(String query, int topK, double minScore, Map<String, Object> filters) {
        return post("/api/news/retrieval/hybrid", query, topK, minScore, filters);
    }

    private List<RetrievalResult> post(String path, String query, int topK, Double minScore, Map<String, Object> filters) {
        RetrievalRequest request = RetrievalRequest.builder()
                .query(query)
                .topK(topK)
                .minScore(minScore)
                .filters(filters)
                .build();
        long startNs = System.nanoTime();
        try {
            RetrievalResponse body;
            if (rpcEnabled) {
                com.example.news.aggregation.rpc.contract.news.RetrievalRequest rpcRequest =
                        objectMapper.convertValue(request, com.example.news.aggregation.rpc.contract.news.RetrievalRequest.class);
                com.example.news.aggregation.rpc.contract.news.RetrievalResponse rpcResponse = switch (path) {
                    case "/api/news/retrieval/keyword" -> newsRetrievalRpcService.keyword(rpcRequest);
                    case "/api/news/retrieval/vector" -> newsRetrievalRpcService.vector(rpcRequest);
                    default -> newsRetrievalRpcService.hybrid(rpcRequest);
                };
                body = objectMapper.convertValue(rpcResponse, RetrievalResponse.class);
            } else {
                String url = retrievalBaseUrl + path;
                log.info("[client] call retrieval|url={} |query={} |topK={} |minScore={} |filters={}",
                        url, querySummary(query), topK, minScore, summarizeFilters(filters));
                ResponseEntity<RetrievalResponse> response = restTemplate.postForEntity(url, request, RetrievalResponse.class);
                body = response.getBody();
            }

            if (body == null || body.getResults() == null) {
                logRetrievalElapsed(path, query, topK, 0, startNs);
                return new ArrayList<>();
            }
            List<RetrievalResult> results = new ArrayList<>();
            for (RetrievalResultDto item : body.getResults()) {
                if (item == null) {
                    continue;
                }
                results.add(RetrievalResult.builder()
                        .articleId(item.getArticleId())
                        .score(item.getScore() != null ? item.getScore() : 0.0)
                        .matchedSnippet(item.getSnippet())
                        .fullContent(item.getFullContent())
                        .publishedAt(item.getPublishedAt())
                        .metadata(item.getMetadata())
                        .build());
            }
            logRetrievalElapsed(path, query, topK, results.size(), startNs);
            return results;
        } catch (Exception e) {
            log.warn("RetrievalClient request failed: path={}, error={}", path, e.getMessage());
            logRetrievalElapsed(path, query, topK, 0, startNs);
            return new ArrayList<>();
        }
    }

    private void logRetrievalElapsed(String path, String query, int topK, int resultCount, long startNs) {
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
        String elapsedSec = formatSeconds(elapsedMs);
        if ("/api/news/retrieval/vector".equals(path)) {
            int callNo = RetrievalMetricsContext.recordVector(elapsedMs);
            log.info("向量检索耗时|callNo={} |path={} |topK={} |resultCount={} |query={} |elapsedSec={}",
                    callNo, path, topK, resultCount, querySummary(query), elapsedSec);
            return;
        }
        if ("/api/news/retrieval/keyword".equals(path)) {
            int callNo = RetrievalMetricsContext.recordEs(elapsedMs);
            log.info("es检索耗时|callNo={} |path={} |topK={} |resultCount={} |query={} |elapsedSec={}",
                    callNo, path, topK, resultCount, querySummary(query), elapsedSec);
            return;
        }
        if ("/api/news/retrieval/hybrid".equals(path)) {
            int callNo = RetrievalMetricsContext.recordHybrid(elapsedMs);
            log.info("es检索耗时|callNo={} |path={} |topK={} |resultCount={} |query={} |elapsedSec={} |mode=HYBRID",
                    callNo, path, topK, resultCount, querySummary(query), elapsedSec);
            log.info("向量检索耗时|callNo={} |path={} |topK={} |resultCount={} |query={} |elapsedSec={} |mode=HYBRID",
                    callNo, path, topK, resultCount, querySummary(query), elapsedSec);
        }
    }

    private String formatSeconds(long elapsedMs) {
        return String.format("%.3f", elapsedMs / 1000.0);
    }

    private String summarizeFilters(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return "{}";
        }
        return filters.toString();
    }

    private String querySummary(String query) {
        if (query == null) {
            return "null";
        }
        String compact = Pattern.compile("\\s+").matcher(query).replaceAll(" ").trim();
        return "len=" + compact.length() + ",value=" + truncate(compact, 120);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class RetrievalRequest {
        private String query;
        private Integer topK;
        private Double minScore;
        private Map<String, Object> filters;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class RetrievalResponse {
        private List<RetrievalResultDto> results;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class RetrievalResultDto {
        private Long articleId;
        private Double score;
        private String snippet;
        private String fullContent;
        private String publishedAt;
        private String metadata;
    }
}
