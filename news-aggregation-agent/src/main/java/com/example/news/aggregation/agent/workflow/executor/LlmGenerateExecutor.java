package com.example.news.aggregation.agent.workflow.executor;

import com.example.news.aggregation.agent.client.GeneratorClient;
import com.example.news.aggregation.agent.client.NewsClient;
import com.example.news.aggregation.agent.workflow.CapabilityExecutor;
import com.example.news.aggregation.agent.workflow.CapabilityMetadata;
import com.example.news.aggregation.agent.workflow.WorkflowContext;
import com.example.news.aggregation.llm.springai.contract.GeneratorDraft;
import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/**
 * LLM 生成能力。
 * 支持摘要/对比/分析/时间线/问答。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmGenerateExecutor implements CapabilityExecutor {

    private final GeneratorClient generatorClient;
    private final NewsClient newsClient;

    @Override
    public String capabilityName() {
        return "llm_generate";
    }

    @Override
    public CapabilityMetadata metadata() {
        return CapabilityMetadata.builder()
                .name("llm_generate")
                .version("v1")
                .description("基于证据生成答案")
                .timeoutMs(15000L)
                .costLevel("HIGH")
                .permissionScope("INTERNAL")
                .build();
    }

    @Override
    public Object execute(Map<String, Object> parameters, WorkflowContext context) {
        String taskFamily = parameters != null && parameters.get("taskFamily") != null
                ? String.valueOf(parameters.get("taskFamily"))
                : context.getTaskFamily();
        String retrievalMode = parameters != null && parameters.get("retrievalMode") != null
                ? String.valueOf(parameters.get("retrievalMode"))
                : null;
        if (retrievalMode == null && context != null && context.getAttributes() != null) {
            Object modeFromContext = context.getAttributes().get("retrievalMode");
            if (modeFromContext != null) {
                retrievalMode = String.valueOf(modeFromContext);
            }
        }
        boolean allowNoEvidence = "NONE".equalsIgnoreCase(retrievalMode);

        // 获取文章详情映射（用于后续根据ID补充标题和图片）
        Map<Long, NewsClient.NewsArticleDto> articleMap = loadArticlesForContext(context.getEvidence());
        // 保存文章详情到 context，供后续组装响应使用
        if (articleMap != null && !articleMap.isEmpty()) {
            context.putAttribute("articleDetails", articleMap);
        }

        List<RetrievalResult> evidence = convertEvidence(context.getEvidence(), articleMap);
        String sessionId = context != null ? context.getSessionId() : "unknown";
        int evidenceCount = evidence != null ? evidence.size() : 0;
        long nonEmptyContentCount = evidence.stream()
                .filter(item -> item.getContent() != null && !item.getContent().isBlank())
                .count();
        String sample = evidence.stream()
                .limit(3)
                .map(item -> {
                    String id = item.getId() != null ? item.getId() : "";
                    int titleLen = item.getTitle() != null ? item.getTitle().trim().length() : 0;
                    int contentLen = item.getContent() != null ? item.getContent().trim().length() : 0;
                    String contentHead = item.getContent() == null ? "" : truncate(item.getContent().trim(), 60);
                    return id + "|t=" + titleLen + "|c=" + contentLen + "|h=" + contentHead;
                })
                .collect(Collectors.joining(","));
        String reason = allowNoEvidence ? "无需证据直答" : "需要证据生成";
        log.info("[链路最终] 开始生成FLOW|agent|node=llm_generate|step=start|sessionId={}|taskFamily={}|evidenceCount={}|nonEmptyContentCount={}|sampleContentLen={}|retrievalMode={}|reason={}|next=LLM生成",
                sessionId, taskFamily, evidenceCount, nonEmptyContentCount, sample, retrievalMode, reason);

        GeneratorDraft draft = generatorClient.generate(context.getQuery(), taskFamily, evidence, retrievalMode);
        if (draft == null || draft.getAnswer() == null || draft.getAnswer().isBlank()) {
            String fallback = "证据不足或质量不足";
            context.putAttribute("answer", fallback);
            log.warn("llm_generate fallback, empty draft.");
            return fallback;
        }

        context.putAttribute("answer", draft.getAnswer());
        context.putAttribute("citations", draft.getCitations());
        log.info("[链路最终] 生成完成FLOW|agent|node=llm_generate|step=end|sessionId={}|answerLength={}|next=响应组装",
                sessionId, draft.getAnswer().length());
        return draft.getAnswer();
    }

    /**
     * 仅为 context 加载文章详情（不转换 evidence）
     */
    private Map<Long, NewsClient.NewsArticleDto> loadArticlesForContext(List<com.example.news.aggregation.agent.tool.dto.RetrievalResult> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = evidence.stream()
                .map(com.example.news.aggregation.agent.tool.dto.RetrievalResult::getArticleId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<NewsClient.NewsArticleDto> articles = newsClient.getArticlesByIds(ids.stream().toList());
        if (articles == null || articles.isEmpty()) {
            return Map.of();
        }
        Map<Long, NewsClient.NewsArticleDto> map = new HashMap<>();
        for (NewsClient.NewsArticleDto article : articles) {
            if (article != null && article.getId() != null) {
                map.put(article.getId(), article);
            }
        }
        return map;
    }

    private List<RetrievalResult> convertEvidence(List<com.example.news.aggregation.agent.tool.dto.RetrievalResult> evidence,
                                                  Map<Long, NewsClient.NewsArticleDto> articleMap) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }

        // 如果没有传入 articleMap，则重新加载
        final Map<Long, NewsClient.NewsArticleDto> effectiveMap;
        if (articleMap == null || articleMap.isEmpty()) {
            effectiveMap = loadArticlesForContext(evidence);
        } else {
            effectiveMap = articleMap;
        }

        List<RetrievalResult> mapped = evidence.stream()
                .map(item -> RetrievalResult.builder()
                        .id(item.getArticleId() != null ? String.valueOf(item.getArticleId()) : "")
                        .title(resolveTitle(item, effectiveMap))
                        .content(resolveContent(item, effectiveMap))
                        .url(resolveUrl(item, effectiveMap))
                        .score(item.getScore() != null ? item.getScore() : 0.0)
                        .source(resolveSource(item, effectiveMap))
                        .build())
                .toList();

        // 过滤掉空内容（content 为空会导致 LLM 侧 context 虽然“有很多条”，但信息量接近 0）
        List<RetrievalResult> nonBlank = mapped.stream()
                .filter(item -> item != null && item.getContent() != null && !item.getContent().isBlank())
                .toList();

        // 二次去重：按 id（即 articleId 字符串）保留 content 更长/score 更高的条目
        Map<String, RetrievalResult> dedup = new LinkedHashMap<>();
        List<RetrievalResult> noId = new java.util.ArrayList<>();
        for (RetrievalResult r : nonBlank) {
            if (r == null || r.getId() == null || r.getId().isBlank()) {
                noId.add(r);
                continue;
            }
            dedup.merge(r.getId(), r, (a, b) -> {
                int aLen = a.getContent() != null ? a.getContent().trim().length() : 0;
                int bLen = b.getContent() != null ? b.getContent().trim().length() : 0;
                if (aLen != bLen) {
                    return bLen > aLen ? b : a;
                }
                double aScore = a.getScore() != null ? a.getScore() : 0.0;
                double bScore = b.getScore() != null ? b.getScore() : 0.0;
                return bScore > aScore ? b : a;
            });
        }
        List<RetrievalResult> cleaned = new java.util.ArrayList<>(dedup.values());
        cleaned.addAll(noId);
        return cleaned;
    }

    private String resolveTitle(com.example.news.aggregation.agent.tool.dto.RetrievalResult item,
                                Map<Long, NewsClient.NewsArticleDto> articleMap) {
        NewsClient.NewsArticleDto article = articleMap.get(item.getArticleId());
        return firstNonBlank(article != null ? article.getTitle() : null, "");
    }

    private String resolveContent(com.example.news.aggregation.agent.tool.dto.RetrievalResult item,
                                  Map<Long, NewsClient.NewsArticleDto> articleMap) {
        NewsClient.NewsArticleDto article = articleMap.get(item.getArticleId());
        // 优先使用 fullContent（中文正文），其次 matchedSnippet，最后 fallback 到 article 内容
        return firstNonBlank(
                item.getFullContent(),     // 中文正文（优先）
                item.getMatchedSnippet(),  // 检索片段
                article != null ? article.getContent() : null,
                article != null ? article.getTitle() : null,
                ""
        );
    }

    private String resolveUrl(com.example.news.aggregation.agent.tool.dto.RetrievalResult item,
                              Map<Long, NewsClient.NewsArticleDto> articleMap) {
        NewsClient.NewsArticleDto article = articleMap.get(item.getArticleId());
        return firstNonBlank(article != null ? article.getUrl() : null, "");
    }

    private String resolveSource(com.example.news.aggregation.agent.tool.dto.RetrievalResult item,
                                 Map<Long, NewsClient.NewsArticleDto> articleMap) {
        NewsClient.NewsArticleDto article = articleMap.get(item.getArticleId());
        return firstNonBlank(article != null ? article.getSource() : null, "AGENT");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (maxLength <= 0) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
