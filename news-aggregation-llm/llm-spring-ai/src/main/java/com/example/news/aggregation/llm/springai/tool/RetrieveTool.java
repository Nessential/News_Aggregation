package com.example.news.aggregation.llm.springai.tool;

import com.example.news.aggregation.embedding.service.EmbeddingService;
import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import com.example.news.aggregation.vector.model.SearchResult;
import com.example.news.aggregation.vector.service.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量检索工具（支持语义检索与混合检索）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrieveTool {

    private final SearchTool searchTool;
    private final VectorStoreService vectorStoreService;
    private final EmbeddingService embeddingService;

    @Value("${app.llm.mcp.tool.retrieval.default-top-k:10}")
    private int defaultTopK;

    @Value("${app.llm.mcp.tool.retrieval.min-similarity:0.5}")
    private double minSimilarity;

    @Value("${app.llm.qdrant.collection-name:news_chunks_zh}")
    private String collectionName;

    @Value("${app.llm.mock-mode:false}")
    private boolean mockMode;

    /**
     * 向量检索（语义检索）。
     */
    @Tool(name = "retrieve_news", description = "向量检索新闻内容")
    public List<RetrievalResult> retrieveNews(String query, int topK) {
        log.info("[工具][retrieve_news] 开始执行|query={} |topK={} |mockMode={}", query, topK, mockMode);

        if (mockMode) {
            return retrieveNewsMock(query, topK);
        }

        try {
            float[] queryVector = embeddingService.embed(query);
            List<SearchResult> searchResults = vectorStoreService.search(
                    collectionName,
                    queryVector,
                    topK,
                    null
            );

            List<RetrievalResult> results = searchResults.stream()
                    .map(this::convertSearchResult)
                    .filter(r -> r.getScore() >= minSimilarity)
                    .toList();

            log.info("[工具][retrieve_news] 执行完成|resultCount={} |minSimilarity={}", results.size(), minSimilarity);
            return results;
        } catch (Exception e) {
            log.error("[工具][retrieve_news] 执行失败|query={}", query, e);
            return new ArrayList<>();
        }
    }

    private List<RetrievalResult> retrieveNewsMock(String query, int topK) {
        List<RetrievalResult> results = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, 20); i++) {
            double score = 0.95 - i * 0.03;
            results.add(RetrievalResult.builder()
                    .id("doc_vector_" + i)
                    .title("向量检索文档" + (i + 1))
                    .content("这是语义检索“" + query + "”的模拟结果。")
                    .url("https://example.com/vector-news/" + i)
                    .score(score)
                    .source("QDRANT")
                    .build());
        }
        return results.stream()
                .filter(r -> r.getScore() >= minSimilarity)
                .toList();
    }

    private RetrievalResult convertSearchResult(SearchResult searchResult) {
        Map<String, Object> payload = searchResult.getPayload();
        return RetrievalResult.builder()
                .id(searchResult.getId())
                .title(getPayloadString(payload, "title"))
                .content(getPayloadString(payload, "content"))
                .url(getPayloadString(payload, "url"))
                .score((double) searchResult.getScore())
                .source("QDRANT")
                .build();
    }

    private String getPayloadString(Map<String, Object> payload, String key) {
        if (payload == null) {
            return "";
        }
        Object value = payload.get(key);
        return value != null ? String.valueOf(value) : "";
    }

    public List<RetrievalResult> retrieveNews(String query) {
        return retrieveNews(query, defaultTopK);
    }

    /**
     * 混合检索：Vector + Keyword，并通过 RRF 融合排序。
     */
    @Tool(name = "hybrid_retrieve_news", description = "混合检索新闻内容")
    public List<RetrievalResult> hybridRetrieve(String query, int topK) {
        log.info("[工具][hybrid_retrieve_news] 开始执行|query={} |topK={}", query, topK);

        List<RetrievalResult> vectorResults = retrieveNews(query, topK * 2);
        List<RetrievalResult> keywordResults = searchTool.searchByKeyword(query, topK * 2);

        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, RetrievalResult> allResults = new HashMap<>();

        final int k = 60;

        for (int i = 0; i < vectorResults.size(); i++) {
            RetrievalResult result = vectorResults.get(i);
            double rrfScore = 1.0 / (k + i + 1);
            rrfScores.put(result.getId(), rrfScores.getOrDefault(result.getId(), 0.0) + rrfScore);
            allResults.put(result.getId(), result);
        }

        for (int i = 0; i < keywordResults.size(); i++) {
            RetrievalResult result = keywordResults.get(i);
            double rrfScore = 1.0 / (k + i + 1);
            rrfScores.put(result.getId(), rrfScores.getOrDefault(result.getId(), 0.0) + rrfScore);
            allResults.putIfAbsent(result.getId(), result);
        }

        List<RetrievalResult> fusedResults = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    RetrievalResult result = allResults.get(entry.getKey());
                    return RetrievalResult.builder()
                            .id(result.getId())
                            .title(result.getTitle())
                            .content(result.getContent())
                            .url(result.getUrl())
                            .score(entry.getValue())
                            .source("HYBRID_RRF")
                            .build();
                })
                .toList();

        log.info("[工具][hybrid_retrieve_news] 执行完成|resultCount={}", fusedResults.size());
        return fusedResults;
    }

    public List<RetrievalResult> hybridRetrieve(String query) {
        return hybridRetrieve(query, defaultTopK);
    }
}