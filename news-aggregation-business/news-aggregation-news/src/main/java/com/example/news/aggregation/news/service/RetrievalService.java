package com.example.news.aggregation.news.service;

import com.example.news.aggregation.embedding.service.EmbeddingService;
import com.example.news.aggregation.es.config.EsProperties;
import com.example.news.aggregation.es.service.ElasticsearchService;
import com.example.news.aggregation.news.dto.RetrievalResultDto;
import com.example.news.aggregation.vector.config.VectorProperties;
import com.example.news.aggregation.vector.model.SearchResult;
import com.example.news.aggregation.vector.service.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    // 默认返回数量上限
    private static final int DEFAULT_TOP_K = 10;
    // 默认最小得分阈值
    private static final double DEFAULT_MIN_SCORE = 0.0;
    // RRF 融合超参数
    private static final int RRF_K = 60;

    private final ElasticsearchService elasticsearchService;
    private final EsProperties esProperties;
    private final VectorStoreService vectorStoreService;
    private final VectorProperties vectorProperties;
    private final EmbeddingService embeddingService;

    /**
     * 关键词检索（ES）
     */
    public List<RetrievalResultDto> keywordSearch(String query, Integer topK) {
        int size = normalizeTopK(topK);
        List<String> fields = List.of("title", "summary", "context", "title_cn", "summary_cn", "context_cn");
        List<Map<String, Object>> esResults = elasticsearchService.search(
                esProperties.getIndexName(), query, size, fields);
        return esResults.stream()
                .map(this::mapEsResult)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 向量检索（Qdrant）
     */
    public List<RetrievalResultDto> vectorSearch(String query, Integer topK, Double minScore) {
        int size = normalizeTopK(topK);
        double min = normalizeMinScore(minScore);
        float[] queryVector = embeddingService.embed(query);
        List<SearchResult> results = vectorStoreService.search(
                vectorProperties.getCollectionName(), queryVector, size, new HashMap<>());
        return results.stream()
                .filter(r -> r.getScore() >= min)
                .map(this::mapVectorResult)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 混合检索（向量 + 关键词 + RRF + 去重）
     */
    public List<RetrievalResultDto> hybridSearch(String query, Integer topK, Double minScore) {
        int size = normalizeTopK(topK);
        double min = normalizeMinScore(minScore);
        List<RetrievalResultDto> vectorResults = vectorSearch(query, size, min);
        List<RetrievalResultDto> keywordResults = keywordSearch(query, size);
        List<RetrievalResultDto> fused = rrfFusion(List.of(vectorResults, keywordResults), size);
        return deduplicate(fused, size);
    }

    /**
     * RRF 融合
     */
    public List<RetrievalResultDto> rrfFusion(List<List<RetrievalResultDto>> lists, Integer topK) {
        int size = normalizeTopK(topK);
        if (lists == null || lists.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, Double> scoreMap = new HashMap<>();
        Map<Long, RetrievalResultDto> baseMap = new HashMap<>();
        for (List<RetrievalResultDto> list : lists) {
            if (list == null) {
                continue;
            }
            for (int i = 0; i < list.size(); i++) {
                RetrievalResultDto item = list.get(i);
                if (item == null || item.getArticleId() == null) {
                    continue;
                }
                double rrfScore = 1.0 / (RRF_K + i + 1);
                scoreMap.merge(item.getArticleId(), rrfScore, Double::sum);
                baseMap.putIfAbsent(item.getArticleId(), item);
            }
        }
        return scoreMap.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(size)
                .map(entry -> {
                    RetrievalResultDto base = baseMap.get(entry.getKey());
                    if (base == null) {
                        return null;
                    }
                    return RetrievalResultDto.builder()
                            .articleId(base.getArticleId())
                            .score(entry.getValue())
                            .snippet(base.getSnippet())
                            .metadata(base.getMetadata())
                            .build();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 去重（按 articleId 保留最高分）
     */
    public List<RetrievalResultDto> deduplicate(List<RetrievalResultDto> results, Integer topK) {
        int size = normalizeTopK(topK);
        if (results == null || results.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, RetrievalResultDto> merged = new HashMap<>();
        for (RetrievalResultDto result : results) {
            if (result == null || result.getArticleId() == null) {
                continue;
            }
            RetrievalResultDto existing = merged.get(result.getArticleId());
            if (existing == null || (result.getScore() != null && existing.getScore() != null
                    && result.getScore() > existing.getScore())) {
                merged.put(result.getArticleId(), result);
            }
        }
        return merged.values().stream()
                .sorted((a, b) -> Double.compare(
                        b.getScore() != null ? b.getScore() : 0.0,
                        a.getScore() != null ? a.getScore() : 0.0))
                .limit(size)
                .collect(Collectors.toList());
    }

    // 将 ES 返回结果转换为统一 DTO
    private RetrievalResultDto mapEsResult(Map<String, Object> result) {
        if (result == null) {
            return null;
        }
        Object idObj = result.get("_id");
        Object scoreObj = result.get("_score");
        Object sourceObj = result.get("_source");
        Long articleId = parseId(idObj);
        Double score = scoreObj instanceof Number ? ((Number) scoreObj).doubleValue() : 0.0;
        String snippet = null;
        String metadata = null;
        if (sourceObj instanceof Map<?, ?> source) {
            Object summary = source.get("summary");
            Object context = source.get("context");
            Object summaryCn = source.get("summary_cn");
            Object contextCn = source.get("context_cn");
            snippet = firstNonEmpty(summary, context, summaryCn, contextCn);
            metadata = source.toString();
            Object newsId = source.get("news_id");
            if (articleId == null) {
                articleId = parseId(newsId);
            }
        }
        if (articleId == null) {
            return null;
        }
        return RetrievalResultDto.builder()
                .articleId(articleId)
                .score(score)
                .snippet(snippet)
                .metadata(metadata)
                .build();
    }

    // 将向量检索结果转换为统一 DTO
    private RetrievalResultDto mapVectorResult(SearchResult result) {
        if (result == null) {
            return null;
        }
        Long articleId = parseId(result.getId());
        if (articleId == null) {
            return null;
        }
        String snippet = null;
        String metadata = null;
        Map<String, Object> payload = result.getPayload();
        if (payload != null) {
            Object content = payload.get("content");
            snippet = content != null ? String.valueOf(content) : null;
            metadata = payload.toString();
        }
        return RetrievalResultDto.builder()
                .articleId(articleId)
                .score((double) result.getScore())
                .snippet(snippet)
                .metadata(metadata)
                .build();
    }

    // 规范化 topK
    private int normalizeTopK(Integer topK) {
        return topK != null && topK > 0 ? topK : DEFAULT_TOP_K;
    }

    // 规范化最小得分阈值
    private double normalizeMinScore(Double minScore) {
        return minScore != null ? minScore : DEFAULT_MIN_SCORE;
    }

    // 将不同类型的 id 统一解析为 Long
    private Long parseId(Object idObj) {
        if (idObj == null) {
            return null;
        }
        if (idObj instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(idObj));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // 选取第一个非空文本作为 snippet
    private String firstNonEmpty(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }
}
