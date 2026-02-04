package com.example.news.aggregation.agent.tool;

import com.example.news.aggregation.agent.tool.dto.RetrievalResult;
import com.example.news.aggregation.es.service.ElasticsearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 关键词搜索工具
 * 基于BM25的精确关键词匹配
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchTool implements Tool<SearchTool.SearchInput, List<RetrievalResult>> {
    
    private final ElasticsearchService elasticsearchService;
    
    @Value("${app.elasticsearch.index-name:news_articles}")
    private String indexName;
    
    @Value("${app.llm.mock-mode:false}")
    private boolean mockMode;
    
    @Override
    public List<RetrievalResult> execute(SearchInput input) {
        if (mockMode) {
            log.info("Mock mode enabled, returning mock search results for keywords: {}", input.keywords);
            return createMockResults();
        }
        
        try {
            // 执行ES搜索 (在title和content字段中搜索)
            List<String> searchFields = List.of("title", "content");
            List<Map<String, Object>> esResults = elasticsearchService.search(
                    indexName,
                    input.keywords,
                    input.topK,
                    searchFields
            );
            
            log.info("Keyword search completed: keywords='{}', results={}", input.keywords, esResults.size());
            
            // 转换为RetrievalResult
            return esResults.stream()
                    .map(result -> RetrievalResult.builder()
                            .articleId(((Number) result.get("id")).longValue())
                            .score(((Number) result.getOrDefault("score", 0.0)).doubleValue())
                            .matchedSnippet((String) result.get("snippet"))
                            .fullContent((String) result.get("content"))
                            .build())
                    .collect(Collectors.toList());
            
        } catch (Exception e) {
            log.error("Keyword search failed for keywords: {}", input.keywords, e);
            throw new RuntimeException("Keyword search failed", e);
        }
    }
    
    @Override
    public String getName() {
        return "search";
    }
    
    @Override
    public String getDescription() {
        return "Search articles using keyword-based BM25 matching";
    }
    
    /**
     * 按关键词搜索
     */
    public List<RetrievalResult> searchByKeyword(String keywords, int topK) {
        SearchInput input = new SearchInput(keywords, topK);
        return execute(input);
    }
    
    private List<RetrievalResult> createMockResults() {
        return List.of(
                RetrievalResult.builder()
                        .articleId(3L)
                        .score(12.5)
                        .matchedSnippet("Mock keyword search result 1")
                        .build(),
                RetrievalResult.builder()
                        .articleId(4L)
                        .score(10.2)
                        .matchedSnippet("Mock keyword search result 2")
                        .build()
        );
    }
    
    /**
     * 搜索输入参数
     */
    public record SearchInput(String keywords, int topK) {
    }
}
