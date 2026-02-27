package com.example.news.aggregation.agent.client;

import com.example.news.aggregation.agent.tool.dto.RetrievalResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Value("${app.news.retrieval.base-url:http://localhost:8082}")
    private String retrievalBaseUrl;

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
        String url = retrievalBaseUrl + path;
        RetrievalRequest request = RetrievalRequest.builder()
                .query(query)
                .topK(topK)
                .minScore(minScore)
                .filters(filters)
                .build();
        try {
            log.info("[client] call retrieval|client=retrieval|step=start|url={} |query={} |topK={} |minScore={} |filters={} |next=news-retrieval",
                    url, querySummary(query), topK, minScore, summarizeFilters(filters));
            ResponseEntity<RetrievalResponse> response = restTemplate.postForEntity(
                    url, request, RetrievalResponse.class);
            RetrievalResponse body = response.getBody();
            if (body == null || body.getResults() == null) {
                log.info("[client] retrieval empty|client=retrieval|step=end|url={} |resultCount=0|next=evidence-merge", url);
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
                        .metadata(item.getMetadata())
                        .build());
            }
            log.info("[client] retrieval done|client=retrieval|step=end|url={} |resultCount={} |next=evidence-merge", url, results.size());

            return results;
        } catch (Exception e) {
            log.warn("RetrievalClient request failed: path={}, error={}", path, e.getMessage());
            return new ArrayList<>();
        }
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
        private String metadata;
    }
}
