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

/**
 * 检索服务客户端（HTTP）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalClient {

    private final RestTemplate restTemplate;

    @Value("${app.news.retrieval.base-url:http://localhost:8082}")
    private String retrievalBaseUrl;

    /**
     * 关键词检索（ES）
     */
    public List<RetrievalResult> keywordSearch(String query, int topK) {
        return post("/api/news/retrieval/keyword", query, topK, null);
    }

    /**
     * 向量检索（Qdrant）
     */
    public List<RetrievalResult> vectorSearch(String query, int topK, double minScore) {
        return post("/api/news/retrieval/vector", query, topK, minScore);
    }

    /**
     * 混合检索（向量 + 关键词 + RRF + 去重）
     */
    public List<RetrievalResult> hybridSearch(String query, int topK, double minScore) {
        return post("/api/news/retrieval/hybrid", query, topK, minScore);
    }

    private List<RetrievalResult> post(String path, String query, int topK, Double minScore) {
        String url = retrievalBaseUrl + path;
        RetrievalRequest request = RetrievalRequest.builder()
                .query(query)
                .topK(topK)
                .minScore(minScore)
                .build();
        try {
            ResponseEntity<RetrievalResponse> response = restTemplate.postForEntity(
                    url, request, RetrievalResponse.class);
            RetrievalResponse body = response.getBody();
            if (body == null || body.getResults() == null) {
                return new ArrayList<>();
            }
            List<RetrievalResult> results = new ArrayList<>();
            for (RetrievalResultDto item : body.getResults()) {
                if (item == null || item.getArticleId() == null) {
                    continue;
                }
                results.add(RetrievalResult.builder()
                        .articleId(item.getArticleId())
                        .score(item.getScore() != null ? item.getScore() : 0.0)
                        .matchedSnippet(item.getSnippet())
                        .metadata(item.getMetadata())
                        .build());
            }
            return results;
        } catch (Exception e) {
            log.warn("RetrievalClient request failed: path={}, error={}", path, e.getMessage());
            return new ArrayList<>();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class RetrievalRequest {
        // 用户查询文本
        private String query;
        // 结果数量上限
        private Integer topK;
        // 向量检索最小得分阈值（可选）
        private Double minScore;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class RetrievalResponse {
        // 检索结果列表
        private List<RetrievalResultDto> results;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class RetrievalResultDto {
        // 文章主键（news_id 或 ES 文档 id）
        private Long articleId;
        // 相关性得分
        private Double score;
        // 证据片段（摘要）
        private String snippet;
        // 原始元数据（序列化后的 source/payload）
        private String metadata;
    }
}
