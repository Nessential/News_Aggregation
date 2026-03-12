package com.example.news.aggregation.llm.springai.tool;

import com.example.news.aggregation.es.service.ElasticsearchService;
import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * ES 关键词检索工具。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchTool {

    private static final List<String> SEARCH_FIELDS = Arrays.asList("title", "content");

    private final ElasticsearchService elasticsearchService;

    @Value("${app.llm.mcp.tool.retrieval.default-top-k:10}")
    private int defaultTopK;

    @Value("${app.llm.elasticsearch.index-name:news_articles}")
    private String indexName;

    @Value("${app.llm.mock-mode:false}")
    private boolean mockMode;

    /**
     * 执行关键词检索。
     */
    @Tool(
            name = "search_news",
            description = "关键词检索新闻内容（ES 倒排检索）。适用：精确词命中场景，如人名/机构名/产品名/地点、明确事件词、明确时间窗口过滤、需要可解释关键词命中。优势是精确召回强；对语义改写、同义表达、跨表述召回弱于向量与混合检索。"
    )
    public List<RetrievalResult> searchByKeyword(String query, int topK) {
        log.info("[工具][search_news] 开始执行|query={} |topK={} |mockMode={}", query, topK, mockMode);

        if (mockMode) {
            return searchByKeywordMock(query, topK);
        }

        try {
            List<Map<String, Object>> esResults = elasticsearchService.search(
                    indexName,
                    query,
                    topK,
                    SEARCH_FIELDS
            );

            List<RetrievalResult> results = esResults.stream()
                    .map(this::convertEsResult)
                    .toList();

            log.info("[工具][search_news] 执行完成|resultCount={}", results.size());
            return results;
        } catch (Exception e) {
            log.error("[工具][search_news] 执行失败|query={}", query, e);
            return new ArrayList<>();
        }
    }

    private List<RetrievalResult> searchByKeywordMock(String query, int topK) {
        List<RetrievalResult> results = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, 20); i++) {
            results.add(RetrievalResult.builder()
                    .id("doc_keyword_" + i)
                    .title("关键词匹配文档" + (i + 1))
                    .content("这是关键词“" + query + "”的模拟检索结果。")
                    .url("https://example.com/news/" + i)
                    .score(0.8 - i * 0.1)
                    .source("ELASTICSEARCH")
                    .build());
        }
        return results;
    }

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

    private String getSourceString(Map<String, Object> source, String key) {
        if (source == null) {
            return "";
        }
        Object value = source.get(key);
        return value != null ? String.valueOf(value) : "";
    }

    public List<RetrievalResult> searchByKeyword(String query) {
        return searchByKeyword(query, defaultTopK);
    }
}
