package com.example.news.aggregation.llm.springai.tool;

import com.alibaba.cloud.ai.mcp.McpTool;
import com.example.news.aggregation.embedding.service.EmbeddingService;
import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import com.example.news.aggregation.vector.model.SearchResult;
import com.example.news.aggregation.vector.service.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Qdrant向量检索工具
 * 使用EmbeddingService生成向量并通过VectorStoreService查询，mockMode用于本地调试兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrieveTool {

    private final SearchTool searchTool;

    /** 向量存储服务（Qdrant适配） */
    private final VectorStoreService vectorStoreService;

    /** 向量化服务（Embedding） */
    private final EmbeddingService embeddingService;

    @Value("${app.llm.mcp.tool.retrieval.default-top-k:10}")
    private int defaultTopK;

    @Value("${app.llm.mcp.tool.retrieval.min-similarity:0.5}")
    private double minSimilarity;

    @Value("${app.llm.qdrant.collection-name:news_vectors}")
    private String collectionName;

    @Value("${app.llm.mock-mode:false}")
    private boolean mockMode;

    /**
     * 向量检索（语义检索）
     *
     * @param query 查询字符串
     * @param topK  返回结果数量
     * @return 检索结果列表
     */
    @McpTool(name = "retrieve_news", description = "向量检索新闻内容")
    public List<RetrievalResult> retrieveNews(String query, int topK) {
        log.info("Executing vector retrieval: query={}, topK={}, mockMode={}", query, topK, mockMode);

        // Mock模式：返回模拟数据
        if (mockMode) {
            return retrieveNewsMock(query, topK);
        }

        // 真实模式：调用向量数据库
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
                    .collect(Collectors.toList());

            log.info("Vector retrieval returned {} results", results.size());
            return results;

        } catch (Exception e) {
            log.error("Vector retrieval failed: query={}", query, e);
            // 优雅降级：返回空列表
            return new ArrayList<>();
        }
    }

    /**
     * Mock模式的向量检索
     */
    private List<RetrievalResult> retrieveNewsMock(String query, int topK) {
        List<RetrievalResult> results = new ArrayList<>();

        for (int i = 0; i < Math.min(topK, 20); i++) {
            double score = 0.95 - i * 0.03;

            results.add(RetrievalResult.builder()
                    .id("doc_vector_" + i)
                    .title("向量检索文档" + (i + 1))
                    .content("这是通过语义向量检索到的相关新闻内容：" + query)
                    .url("https://example.com/vector-news/" + i)
                    .score(score)
                    .source("QDRANT")
                    .build());
        }

        return results.stream()
                .filter(r -> r.getScore() >= minSimilarity)
                .collect(Collectors.toList());
    }

    /**
     * 将SearchResult转换为RetrievalResult
     */
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

    /**
     * 从payload中安全获取字符串值
     */
    private String getPayloadString(Map<String, Object> payload, String key) {
        if (payload == null) {
            return "";
        }
        Object value = payload.get(key);
        return value != null ? String.valueOf(value) : "";
    }

    /**
     * 向量检索（使用默认topK）
     */
    public List<RetrievalResult> retrieveNews(String query) {
        return retrieveNews(query, defaultTopK);
    }

    /**
     * 混合检索（Hybrid: Vector + Keyword with RRF fusion）
     *
     * @param query 查询字符串
     * @param topK  返回结果数量
     * @return 融合后的检索结果列表
     */
    @McpTool(name = "hybrid_retrieve_news", description = "混合检索新闻内容")
    public List<RetrievalResult> hybridRetrieve(String query, int topK) {
        log.info("Executing hybrid retrieval: query={}, topK={}", query, topK);

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
                .collect(Collectors.toList());

        log.info("Hybrid retrieval returned {} results after RRF fusion", fusedResults.size());
        return fusedResults;
    }

    /**
     * 混合检索（使用默认topK）
     */
    public List<RetrievalResult> hybridRetrieve(String query) {
        return hybridRetrieve(query, defaultTopK);
    }
}