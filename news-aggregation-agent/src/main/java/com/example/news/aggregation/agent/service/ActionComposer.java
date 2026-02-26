package com.example.news.aggregation.agent.service;

import com.example.news.aggregation.agent.client.NewsClient;
import com.example.news.aggregation.agent.domain.AgentResponse;
import com.example.news.aggregation.agent.domain.Candidate;
import com.example.news.aggregation.agent.domain.PipelineContext;
import com.example.news.aggregation.agent.domain.PipelineResult;
import com.example.news.aggregation.agent.domain.SessionState;
import com.example.news.aggregation.agent.enums.TaskFamily;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 响应组装器。
 * 根据执行结果构建最终 AgentResponse。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionComposer {

    private final NewsClient newsClient;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 构建 AgentResponse。
     */
    public AgentResponse buildResponse(PipelineResult pipelineResult,
                                       PipelineContext context,
                                       TaskFamily taskFamily) {
        SessionState sessionState = context.getSessionState();
        String sessionId = sessionState.getSessionId();
        log.info("[链路最终] 响应组装开始FLOW|agent|node=action_compose|step=start|sessionId={}|taskFamily={}|next=候选构建", sessionId, taskFamily);

        try {
            // 1. 构建候选文档列表
            List<Candidate> candidates = buildCandidates(pipelineResult.getCandidateIds());
            log.info("[链路最终] 候选文档数量FLOW|agent|node=action_compose|step=candidates|sessionId={}|count={}|next=元数据组装", sessionId, candidates.size());

            // 2. 组装元数据
            AgentResponse.ResponseMetadata metadata = AgentResponse.ResponseMetadata.builder()
                    .retrievedCount(pipelineResult.getCandidateIds() != null
                            ? pipelineResult.getCandidateIds().size() : 0)
                    .llmCallCount(pipelineResult.getLlmCallCount())
                    .pipelineType(determinePipelineType(taskFamily))
                    .remainingBudget(sessionState.getBudget())
                    .build();
            log.info("[链路最终] 响应元数据组装FLOW|agent|node=action_compose|step=metadata|sessionId={}|retrievedCount={}|llmCallCount={}|pipelineType={}|next=最终响应", sessionId, metadata.getRetrievedCount(), metadata.getLlmCallCount(), metadata.getPipelineType());

            // 3. 组装最终响应

            return AgentResponse.builder()
                    .sessionId(sessionId)
                    .answer(pipelineResult.getAnswer())
                    .candidates(candidates)
                    .citations(pipelineResult.getCitations())
                    .taskFamily(taskFamily)
                    .needsClarification(false)
                    .timestamp(LocalDateTime.now())
                    .executionTimeMs(pipelineResult.getExecutionTimeMs())
                    .metadata(metadata)
                    .build();

        } catch (Exception e) {
            log.error("Failed to build response", e);
            return buildErrorResponse(sessionId, e.getMessage());
        }
    }

    /**
     * 构建候选文档列表。
     */
    private List<Candidate> buildCandidates(List<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            log.info("关键路径-拉取候选文档开始: count={}", articleIds.size());
            // 批量拉取文章详情
            Map<Long, NewsClient.NewsArticleDto> articleMap = newsClient.getArticlesByIds(articleIds)
                    .stream()
                    .collect(Collectors.toMap(NewsClient.NewsArticleDto::getId, article -> article));

            // 映射为 Candidate
            List<Candidate> candidates = articleIds.stream()
                    .map(id -> {
                        NewsClient.NewsArticleDto article = articleMap.get(id);
                        if (article == null) {
                            log.warn("Article not found: {}", id);
                            return null;
                        }
                        return buildCandidate(article);
                    })
                    .filter(candidate -> candidate != null)
                    .collect(Collectors.toList());
            log.info("关键路径-候选文档组装完成: count={}", candidates.size());
            return candidates;

        } catch (Exception e) {
            log.error("Failed to fetch article details", e);
            return buildFallbackCandidates(articleIds);
        }
    }

    /**
     * 构建 Candidate。
     */
    private Candidate buildCandidate(NewsClient.NewsArticleDto article) {
        return Candidate.builder()
                .articleId(article.getId())
                .title(article.getTitle())
                .url(article.getUrl())
                .snippet(truncate(article.getContent(), 200))
                .source(article.getSource())
                .publishedAt(article.getPublishedAt() != null
                        ? article.getPublishedAt() : null)
                .build();
    }

    /**
     * 构建兜底候选文档列表。
     */
    private List<Candidate> buildFallbackCandidates(List<Long> articleIds) {
        return articleIds.stream()
                .map(id -> Candidate.builder()
                        .articleId(id)
                        .title("Article " + id)
                        .snippet("Details unavailable")
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 构建错误响应。
     */
    private AgentResponse buildErrorResponse(String sessionId, String errorMessage) {
        return AgentResponse.builder()
                .sessionId(sessionId)
                .answer("内部错误: " + errorMessage)
                .candidates(new ArrayList<>())
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * 判断 pipeline 类型。
     */
    private String determinePipelineType(TaskFamily taskFamily) {
        return switch (taskFamily) {
            case QA -> "QA";
            case SUMMARY, COMPARE, TIMELINE, DEEP_DIVE -> "Summary";
            case SEARCH, MONITORING -> "Search";
            default -> "Unknown";
        };
    }

    /**
     * 内容截断。
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
