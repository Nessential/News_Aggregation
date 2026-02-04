package com.example.news.aggregation.agent.tool;

import com.example.news.aggregation.agent.tool.dto.RetrievalResult;
import com.example.news.aggregation.embedding.service.EmbeddingService;
import com.example.news.aggregation.vector.service.VectorStoreService;
import com.example.news.aggregation.vector.model.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 向量检索工具
 * 基于语义相似度检索相关文章
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrieveTool implements Tool<RetrieveTool.RetrieveInput, List<RetrievalResult>> {
    
    private final VectorStoreService vectorStoreService;
    private final EmbeddingService embeddingService;
    
    @Value("${app.qdrant.collection-name:news_articles}")
    private String collectionName;
    
    @Value("${app.llm.mock-mode:false}")
    private boolean mockMode;
    
    @Override
    public List<RetrievalResult> execute(RetrieveInput input) {
        if (mockMode) {
            log.info("Mock mode enabled, returning mock results for query: {}", input.query);
            return createMockResults();
        }
        
        try {
            // 使用智谱 AI 生成 query 的向量 (2048维)
            float[] queryVector = embeddingService.embed(input.query);
            log.debug("Generated embedding vector with dimension: {}", queryVector.length);
            
            // 执行向量检索 (添加score过滤)
            Map<String, Object> filter = new HashMap<>();
            // 注意: Qdrant的filter语法需要根据实际情况调整
            List<SearchResult> searchResults = vectorStoreService.search(
                    collectionName, 
                    queryVector, 
                    input.topK,
                    filter
            );
            
            // 过滤低于minScore的结果
            List<SearchResult> filteredResults = searchResults.stream()
                    .filter(result -> result.getScore() >= input.minScore)
                    .collect(Collectors.toList());
            
            log.info("Vector retrieval completed: query='{}', results={}", input.query, filteredResults.size());
            
            // 转换为RetrievalResult
            return filteredResults.stream()
                    .map(result -> {
                        Map<String, Object> payload = result.getPayload();
                        return RetrievalResult.builder()
                                .articleId(Long.parseLong(result.getId()))
                                .score((double) result.getScore())
                                .matchedSnippet(payload != null ? (String) payload.get("content") : "")
                                .metadata(payload != null ? payload.toString() : "")
                                .build();
                    })
                    .collect(Collectors.toList());
            
        } catch (Exception e) {
            log.error("Vector retrieval failed for query: {}", input.query, e);
            throw new RuntimeException("Vector retrieval failed", e);
        }
    }
    
    @Override
    public String getName() {
        return "retrieve";
    }
    
    @Override
    public String getDescription() {
        return "Retrieve relevant articles using semantic similarity search";
    }
    
    /**
     * 混合检索 (向量 + 关键词)
     */
    public List<RetrievalResult> hybridRetrieve(String query, int topK, double minScore) {
        RetrieveInput input = new RetrieveInput(query, topK, minScore);
        return execute(input);
    }
    
    private List<RetrievalResult> createMockResults() {
        return List.of(
                RetrievalResult.builder()
                        .articleId(1L)
                        .score(0.95)
                        .matchedSnippet("Mock article 1 snippet")
                        .build(),
                RetrievalResult.builder()
                        .articleId(2L)
                        .score(0.88)
                        .matchedSnippet("Mock article 2 snippet")
                        .build()
        );
    }
    
    /**
     * 检索输入参数
     */
    public record RetrieveInput(String query, int topK, double minScore) {
    }
}
