package com.example.news.aggregation.news.service;

import com.example.news.aggregation.embedding.service.EmbeddingService;
import com.example.news.aggregation.es.config.EsProperties;
import com.example.news.aggregation.es.service.ElasticsearchService;
import com.example.news.aggregation.news.dto.RetrievalResultDto;
import com.example.news.aggregation.vector.config.VectorProperties;
import com.example.news.aggregation.vector.model.SearchResult;
import com.example.news.aggregation.vector.service.VectorStoreService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
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
        return keywordSearch(query, topK, null);
    }

    /**
     * 关键词检索（ES）
     */
    public List<RetrievalResultDto> keywordSearch(String query, Integer topK, Map<String, Object> filters) {
        int size = normalizeTopK(topK);
        FilterCriteria criteria = parseFilters(filters);
        String finalQuery = buildQuery(query, criteria);
        Map<String, Object> esFilters = buildEsFilters(criteria);
        String sortBy = criteria.getSortBy();
        log.info("[DIAG][retrieval][keyword] start|query={} |finalQuery={} |topK={} |filters={} |sortBy={}",
                querySummary(query), querySummary(finalQuery), size, summarizeFilters(esFilters), sortBy);

        List<String> fields = List.of("title", "summary", "context", "title_cn", "summary_cn", "context_cn");
        Map<String, Object> activeFilters = new HashMap<>(esFilters);
        List<Map<String, Object>> esResults = searchEs(finalQuery, size, fields, activeFilters, sortBy);
        List<RetrievalResultDto> mappedResults = mapEsResults(esResults);
        // Fallback-1: language filter is often over-strict when source language is persisted as 'en'.
        if (mappedResults.isEmpty() && activeFilters.containsKey("language")) {
            Object removed = activeFilters.remove("language");
            log.info("[DIAG][retrieval][keyword] fallback|reason=empty-with-language-filter|removedLanguage={} |retryFilters={}",
                    removed, summarizeFilters(activeFilters));
            esResults = searchEs(finalQuery, size, fields, activeFilters, sortBy);
            mappedResults = mapEsResults(esResults);
        }
        // Fallback-2: time window may be too narrow for current data.
        if (mappedResults.isEmpty() && activeFilters.containsKey("publication_time")) {
            Object removed = activeFilters.remove("publication_time");
            log.info("[DIAG][retrieval][keyword] fallback|reason=empty-with-time-range|removedPublicationTime={} |retryFilters={}",
                    removed, summarizeFilters(activeFilters));
            esResults = searchEs(finalQuery, size, fields, activeFilters, sortBy);
            mappedResults = mapEsResults(esResults);
        }
        // Fallback-3: clear all remaining filters for robust recall.
        if (mappedResults.isEmpty() && !activeFilters.isEmpty()) {
            Map<String, Object> removed = new HashMap<>(activeFilters);
            activeFilters.clear();
            log.info("[DIAG][retrieval][keyword] fallback|reason=empty-with-all-filters|removedFilters={} |retryFilters={}",
                    summarizeFilters(removed), summarizeFilters(activeFilters));
            esResults = searchEs(finalQuery, size, fields, activeFilters, sortBy);
            mappedResults = mapEsResults(esResults);
        }
        log.info("[DIAG][retrieval][keyword] end|index={} |esRawCount={} |mappedCount={}",
                esProperties.getIndexName(), esResults.size(), mappedResults.size());
        return mappedResults;
    }

    /**
     * 向量检索（Qdrant）
     */
    public List<RetrievalResultDto> vectorSearch(String query, Integer topK, Double minScore) {
        return vectorSearch(query, topK, minScore, null);
    }

    /**
     * 向量检索（Qdrant）
     */
    public List<RetrievalResultDto> vectorSearch(String query, Integer topK, Double minScore, Map<String, Object> filters) {
        int size = normalizeTopK(topK);
        double min = normalizeMinScore(minScore);
        FilterCriteria criteria = parseFilters(filters);
        String collectionName = resolveCollectionName(criteria);
        float[] queryVector = embeddingService.embed(query);
        Map<String, Object> vectorFilters = buildVectorFilters(criteria);
        log.info("[DIAG][retrieval][vector] start|query={} |topK={} |minScore={} |collection={} |language={} |filters={} |vectorDim={}",
                querySummary(query), size, min, collectionName, criteria.getLanguage(), summarizeFilters(vectorFilters), queryVector.length);
        List<SearchResult> rawResults = vectorStoreService.search(
                collectionName, queryVector, size, vectorFilters);
        VectorEvaluation evaluation = evaluateVectorResults(rawResults, min);
        int belowScoreCount = evaluation.getBelowScoreCount();
        int invalidIdCount = evaluation.getInvalidIdCount();
        List<String> invalidIdSamples = evaluation.getInvalidIdSamples();
        List<RetrievalResultDto> mappedResults = evaluation.getMappedResults();
        if (mappedResults.isEmpty() && vectorFilters.containsKey("published_at")) {
            Map<String, Object> relaxed = new HashMap<>(vectorFilters);
            Object removed = relaxed.remove("published_at");
            log.info("[DIAG][retrieval][vector] fallback|reason=empty-with-time-range|removedPublishedAt={} |retryFilters={}",
                    removed, summarizeFilters(relaxed));
            rawResults = vectorStoreService.search(collectionName, queryVector, size, relaxed);
            evaluation = evaluateVectorResults(rawResults, min);
            belowScoreCount = evaluation.getBelowScoreCount();
            invalidIdCount = evaluation.getInvalidIdCount();
            invalidIdSamples = evaluation.getInvalidIdSamples();
            mappedResults = evaluation.getMappedResults();
        }
        // Fallback-2: if all candidates are below threshold, lower threshold to keep recall.
        if (mappedResults.isEmpty() && rawResults != null && !rawResults.isEmpty()
                && belowScoreCount == rawResults.size() && min > 0.0d) {
            log.info("[DIAG][retrieval][vector] fallback|reason=all-below-min-score|oldMinScore={} |newMinScore=0.0", min);
            evaluation = evaluateVectorResults(rawResults, 0.0d);
            belowScoreCount = evaluation.getBelowScoreCount();
            invalidIdCount = evaluation.getInvalidIdCount();
            invalidIdSamples = evaluation.getInvalidIdSamples();
            mappedResults = evaluation.getMappedResults();
        }
        log.info("[DIAG][retrieval][vector] end|collection={} |rawCount={} |belowScoreCount={} |invalidIdCount={} |invalidIdSamples={} |finalCount={}",
                collectionName, rawResults.size(), belowScoreCount, invalidIdCount, invalidIdSamples, mappedResults.size());
        return mappedResults;
    }

    /**
     * 混合检索（向量 + 关键词 + RRF + 去重）
     */
    public List<RetrievalResultDto> hybridSearch(String query, Integer topK, Double minScore) {
        return hybridSearch(query, topK, minScore, null);
    }

    /**
     * 混合检索（向量 + 关键词 + RRF + 去重）
     */
    public List<RetrievalResultDto> hybridSearch(String query, Integer topK, Double minScore, Map<String, Object> filters) {
        int size = normalizeTopK(topK);
        double min = normalizeMinScore(minScore);
        log.info("[DIAG][retrieval][hybrid] start|query={} |topK={} |minScore={} |filters={}",
                querySummary(query), size, min, summarizeFilters(filters));
        List<RetrievalResultDto> vectorResults = vectorSearch(query, size, min, filters);
        List<RetrievalResultDto> keywordResults = keywordSearch(query, size, filters);
        List<RetrievalResultDto> fused = rrfFusion(List.of(vectorResults, keywordResults), size);
        List<RetrievalResultDto> deduped = deduplicate(fused, size);
        log.info("[DIAG][retrieval][hybrid] end|vectorCount={} |keywordCount={} |fusedCount={} |dedupedCount={}",
                vectorResults.size(), keywordResults.size(), fused.size(), deduped.size());
        return deduped;
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
        Long articleId = resolveVectorArticleId(result);
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

    /**
     * 构建综合查询文本
     */
    private String buildQuery(String query, FilterCriteria criteria) {
        StringBuilder builder = new StringBuilder();
        if (query != null && !query.isBlank()) {
            builder.append(query.trim());
        }
        if (criteria.getKeywords() != null && !criteria.getKeywords().isEmpty()) {
            if (builder.length() > 0) {
                builder.append(" ");
            }
            builder.append(String.join(" ", criteria.getKeywords()));
        }
        if (criteria.getTopic() != null && !criteria.getTopic().isBlank()) {
            if (builder.length() > 0) {
                builder.append(" ");
            }
            builder.append(criteria.getTopic().trim());
        }
        return builder.toString().trim();
    }

    /**
     * 构建ES过滤条件
     */
    private Map<String, Object> buildEsFilters(FilterCriteria criteria) {
        Map<String, Object> filters = new HashMap<>();
        if (criteria.getCategory() != null && !criteria.getCategory().isBlank()) {
            filters.put("category", criteria.getCategory());
        }
        if (criteria.getLanguage() != null && !criteria.getLanguage().isBlank()) {
            filters.put("language", criteria.getLanguage());
        }
        String source = criteria.getSource();
        if ((source == null || source.isBlank()) && criteria.getPublisher() != null) {
            source = criteria.getPublisher();
        }
        if (source != null && !source.isBlank()) {
            filters.put("source", source);
        }
        if (criteria.getStartTime() != null || criteria.getEndTime() != null) {
            Map<String, Object> range = new HashMap<>();
            if (criteria.getStartTime() != null) {
                range.put("gte", criteria.getStartTime());
            }
            if (criteria.getEndTime() != null) {
                range.put("lte", criteria.getEndTime());
            }
            filters.put("publication_time", range);
        }
        return filters.isEmpty() ? new HashMap<>() : filters;
    }

    /**
     * 构建向量检索过滤条件
     */
    private Map<String, Object> buildVectorFilters(FilterCriteria criteria) {
        Map<String, Object> filters = new HashMap<>();
        if (criteria.getCategory() != null && !criteria.getCategory().isBlank()) {
            filters.put("category", criteria.getCategory());
        }
        String source = criteria.getSource();
        if ((source == null || source.isBlank()) && criteria.getPublisher() != null) {
            source = criteria.getPublisher();
        }
        if (source != null && !source.isBlank()) {
            filters.put("source", source);
        }
        if (criteria.getStartTime() != null || criteria.getEndTime() != null) {
            Map<String, Object> range = new HashMap<>();
            if (criteria.getStartTime() != null) {
                range.put("gte", criteria.getStartTime());
            }
            if (criteria.getEndTime() != null) {
                range.put("lte", criteria.getEndTime());
            }
            filters.put("published_at", range);
        }
        return filters;
    }

    private String resolveCollectionName(FilterCriteria criteria) {
        if (criteria != null && criteria.getLanguage() != null) {
            String lang = criteria.getLanguage().trim().toLowerCase(Locale.ROOT);
            if ("zh".equals(lang)) {
                return vectorProperties.getCollectionNameZh();
            }
            if ("en".equals(lang)) {
                return vectorProperties.getCollectionNameEn();
            }
        }
        return vectorProperties.getCollectionName();
    }

    /**
     * 解析过滤条件
     */
    private FilterCriteria parseFilters(Map<String, Object> filters) {
        FilterCriteria criteria = new FilterCriteria();
        if (filters == null || filters.isEmpty()) {
            return criteria;
        }
        criteria.setTimeRange(getString(filters.get("timeRange")));
        criteria.setStartTime(parseDateToMillis(filters.get("startDate")));
        criteria.setEndTime(parseDateToMillis(filters.get("endDate")));
        criteria.setKeywords(parseStringList(filters.get("keywords")));
        criteria.setTopic(getString(filters.get("topic")));
        criteria.setCategory(getString(filters.get("category")));
        criteria.setLanguage(getString(filters.get("language")));
        criteria.setRegion(getString(filters.get("region")));
        criteria.setSource(getString(filters.get("source")));
        criteria.setPublisher(getString(filters.get("publisher")));
        criteria.setSortBy(getString(filters.get("sortBy")));

        if (criteria.getStartTime() == null && criteria.getEndTime() == null && criteria.getTimeRange() != null) {
            Long rangeMs = parseTimeRange(criteria.getTimeRange());
            if (rangeMs != null) {
                long now = System.currentTimeMillis();
                criteria.setStartTime(now - rangeMs);
                criteria.setEndTime(now);
            }
        } else if (criteria.getStartTime() != null && criteria.getEndTime() == null) {
            criteria.setEndTime(System.currentTimeMillis());
        }

        return criteria;
    }

    private String getString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private List<String> parseStringList(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item));
                }
            }
            return result.isEmpty() ? null : result;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        return Arrays.asList(text.split("\\s*,\\s*"));
    }

    /**
     * 解析时间范围（如 7d/1w/24h）
     */
    private Long parseTimeRange(String timeRange) {
        if (timeRange == null || timeRange.isBlank()) {
            return null;
        }
        String raw = timeRange.trim().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d+)\\s*([dhmw])").matcher(raw);
        if (matcher.find()) {
            long value = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2);
            return switch (unit) {
                case "h" -> value * 3600_000L;
                case "d" -> value * 24 * 3600_000L;
                case "w" -> value * 7 * 24 * 3600_000L;
                case "m" -> value * 30 * 24 * 3600_000L;
                default -> null;
            };
        }
        java.util.regex.Matcher wordMatcher = java.util.regex.Pattern
                .compile("(\\d+)\\s*(day|days|week|weeks|hour|hours|month|months)")
                .matcher(raw);
        if (wordMatcher.find()) {
            long value = Long.parseLong(wordMatcher.group(1));
            String unit = wordMatcher.group(2);
            if (unit.startsWith("hour")) {
                return value * 3600_000L;
            }
            if (unit.startsWith("day")) {
                return value * 24 * 3600_000L;
            }
            if (unit.startsWith("week")) {
                return value * 7 * 24 * 3600_000L;
            }
            if (unit.startsWith("month")) {
                return value * 30 * 24 * 3600_000L;
            }
        }
        return null;
    }

    /**
     * 解析日期字符串为毫秒
     */
    private Long parseDateToMillis(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        if (text.matches("\\d{13}")) {
            return Long.parseLong(text);
        }
        try {
            LocalDate date = LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
            return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignore) {
            // ignore
        }
        try {
            LocalDateTime dateTime = LocalDateTime.parse(text, DateTimeFormatter.ISO_DATE_TIME);
            return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignore) {
            return null;
        }
    }

    /**
     * 过滤条件解析结构
     */
    @Data
    private static class FilterCriteria {
        private String timeRange;
        private Long startTime;
        private Long endTime;
        private List<String> keywords;
        private String topic;
        private String category;
        private String language;
        private String region;
        private String source;
        private String publisher;
        private String sortBy;
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

    private String summarizeFilters(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return "{}";
        }
        return filters.toString();
    }

    private List<Map<String, Object>> searchEs(String query,
                                               int size,
                                               List<String> fields,
                                               Map<String, Object> filters,
                                               String sortBy) {
        return elasticsearchService.search(esProperties.getIndexName(), query, size, fields, filters, sortBy);
    }

    private List<RetrievalResultDto> mapEsResults(List<Map<String, Object>> esResults) {
        return esResults.stream()
                .map(this::mapEsResult)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private VectorEvaluation evaluateVectorResults(List<SearchResult> rawResults, double minScore) {
        int belowScoreCount = 0;
        int invalidIdCount = 0;
        List<String> invalidIdSamples = new ArrayList<>();
        List<RetrievalResultDto> mappedResults = new ArrayList<>();
        for (SearchResult result : rawResults) {
            if (result == null) {
                continue;
            }
            if (result.getScore() < minScore) {
                belowScoreCount++;
                continue;
            }
            if (resolveVectorArticleId(result) == null) {
                invalidIdCount++;
                if (invalidIdSamples.size() < 3) {
                    invalidIdSamples.add(String.valueOf(result.getId()));
                }
                continue;
            }
            RetrievalResultDto dto = mapVectorResult(result);
            if (dto != null) {
                mappedResults.add(dto);
            }
        }
        return new VectorEvaluation(mappedResults, belowScoreCount, invalidIdCount, invalidIdSamples);
    }

    private Long resolveVectorArticleId(SearchResult result) {
        if (result == null) {
            return null;
        }
        Map<String, Object> payload = result.getPayload();
        if (payload != null) {
            Long newsId = parseId(payload.get("news_id"));
            if (newsId != null) {
                return newsId;
            }
            Long articleId = parseId(payload.get("article_id"));
            if (articleId != null) {
                return articleId;
            }
        }
        return parseId(result.getId());
    }

    private String querySummary(String query) {
        if (query == null) {
            return "null";
        }
        String compact = Pattern.compile("\\s+").matcher(query).replaceAll(" ").trim();
        boolean hasCjk = compact.codePoints().anyMatch(this::isCjk);
        return "len=" + compact.length() + ",hasCjk=" + hasCjk + ",value=" + truncate(compact, 120);
    }

    private boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    @Data
    private static class VectorEvaluation {
        private final List<RetrievalResultDto> mappedResults;
        private final int belowScoreCount;
        private final int invalidIdCount;
        private final List<String> invalidIdSamples;
    }
}
