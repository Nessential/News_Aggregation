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

        List<RetrievalResult> evidence = convertEvidence(context.getEvidence());
        String sessionId = context != null ? context.getSessionId() : "unknown";
        int evidenceCount = evidence != null ? evidence.size() : 0;
        long nonEmptyContentCount = evidence.stream()
                .filter(item -> item.getContent() != null && !item.getContent().isBlank())
                .count();
        String sample = evidence.stream()
                .limit(3)
                .map(item -> item.getId() + ":" + (item.getContent() == null ? 0 : item.getContent().length()))
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

    private List<RetrievalResult> convertEvidence(List<com.example.news.aggregation.agent.tool.dto.RetrievalResult> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }

        Map<Long, NewsClient.NewsArticleDto> articleMap = loadArticles(evidence);
        return evidence.stream()
                .map(item -> RetrievalResult.builder()
                        .id(item.getArticleId() != null ? String.valueOf(item.getArticleId()) : "")
                        .title(resolveTitle(item, articleMap))
                        .content(resolveContent(item, articleMap))
                        .url(resolveUrl(item, articleMap))
                        .score(item.getScore() != null ? item.getScore() : 0.0)
                        .source(resolveSource(item, articleMap))
                        .build())
                .collect(Collectors.toList());
    }

    private Map<Long, NewsClient.NewsArticleDto> loadArticles(List<com.example.news.aggregation.agent.tool.dto.RetrievalResult> evidence) {
        Set<Long> ids = evidence.stream()
                .map(com.example.news.aggregation.agent.tool.dto.RetrievalResult::getArticleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
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

    private String resolveTitle(com.example.news.aggregation.agent.tool.dto.RetrievalResult item,
                                Map<Long, NewsClient.NewsArticleDto> articleMap) {
        NewsClient.NewsArticleDto article = articleMap.get(item.getArticleId());
        return firstNonBlank(article != null ? article.getTitle() : null, "");
    }

    private String resolveContent(com.example.news.aggregation.agent.tool.dto.RetrievalResult item,
                                  Map<Long, NewsClient.NewsArticleDto> articleMap) {
        NewsClient.NewsArticleDto article = articleMap.get(item.getArticleId());
        return firstNonBlank(
                item.getMatchedSnippet(),
                item.getFullContent(),
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
}
