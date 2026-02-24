package com.example.news.aggregation.llm.springai.tool;

import com.alibaba.cloud.ai.mcp.McpTool;
import com.example.news.aggregation.es.service.ElasticsearchService;
import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * ES关键词检索工具
 * 通过ElasticsearchService执行关键词检索，mockMode用于本地调试兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchTool {

    /** ES查询服务（真实模式使用） */
    private final ElasticsearchService elasticsearchService;

    @Value("${app.llm.mcp.tool.retrieval.default-top-k:10}")
    private int defaultTopK;

    @Value("${app.llm.elasticsearch.index-name:news_articles}")
    private String indexName;

    @Value("${app.llm.mock-mode:false}")
    private boolean mockMode;

    /** 默认检索字段 */
    private static final List<String> SEARCH_FIELDS = Arrays.asList("title", "content");

    /**
     * 执行关键词检索
     *
     * @param query 查询字符串
     * @param topK  返回结果数量
     * @return 检索结果列表
     */
    @McpTool(name = "search_news", description = "关键词检索新闻内容")
    public List<RetrievalResult> searchByKeyword(String query, int topK) {
        log.info("Executing keyword search: query={}, topK={}, mockMode={}", query, topK, mockMode);

        // Mock模式：返回模拟数据
        if (mockMode) {
            return searchByKeywordMock(query, topK);
        }

        // 真实模式：调用ES检索
        try {
            List<Map<String, Object>> esResults = elasticsearchService.search(
                    indexName,
                    query,
                    topK,
                    SEARCH_FIELDS
            );

            List<RetrievalResult> results = esResults.stream()
                    .map(this::convertEsResult)
                    .collect(java.util.stream.Collectors.toList());

            log.info("Keyword search returned {} results", results.size());
            return results;

        } catch (Exception e) {
            log.error("Keyword search failed: query={}", query, e);
            // 优雅降级：返回空列表
            return new ArrayList<>();
        }
    }

    /**
     * Mock模式的关键词检索
     */
    private List<RetrievalResult> searchByKeywordMock(String query, int topK) {
        List<RetrievalResult> results = new ArrayList<>();

        for (int i = 0; i < Math.min(topK, 20); i++) {
            results.add(RetrievalResult.builder()
                    .id("doc_keyword_" + i)
                    .title("关键词匹配文档" + (i + 1))
                    .content("这是通过关键词'" + query + "'检索到的文档内容，包含相关新闻信息。")
                    .url("https://example.com/news/" + i)
                    .score(0.8 - i * 0.1)
                    .source("ELASTICSEARCH")
                    .build());
        }

        return results;
    }

    /**
     * 将ES结果转换为RetrievalResult
     */
    @SuppressWarnings("unchecked")
    private RetrievalResult convertEsResult(Map<String, Object> esResult) {
        String id = (String) esResult.get("_id");
        Double score = esResult.get("_score") instanceof Number
                ? ((Number) esResult.get("_score")).doubleValue()
                : 0.0;

        Map<String, Object> source = (Map<String, Object>) esResult.get("_source");

        return RetrievalResult.builder()
                .id(id != null ? id : "")
                .title(getSourceString(source, "title"))
                .content(getSourceString(source, "content"))
                .url(getSourceString(source, "url"))
                .score(score)
                .source("ELASTICSEARCH")
                .build();
    }

    /**
     * 从source中安全获取字符串值
     */
    private String getSourceString(Map<String, Object> source, String key) {
        if (source == null) {
            return "";
        }
        Object value = source.get(key);
        return value != null ? String.valueOf(value) : "";
    }

    /**
     * 执行关键词检索（使用默认topK）
     */
    public List<RetrievalResult> searchByKeyword(String query) {
        return searchByKeyword(query, defaultTopK);
    }
}