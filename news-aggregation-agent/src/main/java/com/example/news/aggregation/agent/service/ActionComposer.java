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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 响应组装器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionComposer {

    private final NewsClient newsClient;

    public AgentResponse buildResponse(PipelineResult pipelineResult,
                                       PipelineContext context,
                                       TaskFamily taskFamily) {
        SessionState sessionState = context.getSessionState();
        String sessionId = sessionState.getSessionId();
        log.info("[action-compose] 开始组装响应|sessionId={} |taskFamily={}", sessionId, taskFamily);

        try {
            // 根据答案中引用的新闻ID构建候选列表
            List<String> citationIds = pipelineResult.getCitations();
            List<Candidate> candidates;
            if (citationIds != null && !citationIds.isEmpty()) {
                // 将字符串ID转换为Long类型
                List<Long> citedArticleIds = citationIds.stream()
                        .map(id -> {
                            try {
                                return Long.parseLong(id);
                            } catch (NumberFormatException e) {
                                return null;
                            }
                        })
                        .filter(id -> id != null)
                        .collect(Collectors.toList());
                candidates = buildCandidatesByCitationIds(citedArticleIds);
                log.info("[action-compose] 根据引用构建候选|citationCount={} |candidateCount={}", citationIds.size(), candidates.size());
            } else {
                // 兼容旧逻辑：使用检索返回的候选ID
                candidates = buildCandidates(pipelineResult.getCandidateIds());
            }

            Map<String, Object> extraData = pipelineResult.getExtraData();

            AgentResponse.ResponseMetadata metadata = AgentResponse.ResponseMetadata.builder()
                    .retrievedCount(pipelineResult.getCandidateIds() != null ? pipelineResult.getCandidateIds().size() : 0)
                    .llmCallCount(pipelineResult.getLlmCallCount())
                    .pipelineType(determinePipelineType(taskFamily))
                    .remainingBudget(sessionState.getBudget())
                    .qualityGateTriggered(readBoolean(extraData, "qualityGateTriggered", false))
                    .qualityWarningCount(readInteger(extraData, "qualityWarningCount", 0))
                    .qualityWarnings(readStringList(extraData, "qualityWarnings"))
                    .schemaValidationMode(readString(extraData, "schemaValidationMode", ""))
                    .executionSchemaVersion(readString(extraData, "executionSchemaVersion", ""))
                    .executionSemanticVersion(readString(extraData, "executionSemanticVersion", ""))
                    .inputValidationCount(readInteger(extraData, "inputValidationCount", 0))
                    .outputValidationCount(readInteger(extraData, "outputValidationCount", 0))
                    .degradeOutputTriggered(readBoolean(extraData, "degradeOutputTriggered", false))
                    .degradeReasonCode(readString(extraData, "degradeReasonCode", ""))
                    .degradeStepId(readString(extraData, "degradeStepId", ""))
                    .executionRunId(readString(extraData, "executionRunId", ""))
                    .executionRunStatus(readString(extraData, "executionRunStatus", ""))
                    .currentExecutionStep(readString(extraData, "currentExecutionStep", ""))
                    .effectLatchStatus(readString(extraData, "effectLatchStatus", ""))
                    .executionStepAttempt(readInteger(extraData, "executionStepAttempt", 0))
                    .executionReasonCode(readString(extraData, "executionReasonCode", ""))
                    .build();

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
            log.error("[action-compose] 组装响应失败", e);
            return buildErrorResponse(sessionId, e.getMessage());
        }
    }

    private List<Candidate> buildCandidates(List<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            Map<Long, NewsClient.NewsArticleDto> articleMap = newsClient.getArticlesByIds(articleIds)
                    .stream()
                    .collect(Collectors.toMap(NewsClient.NewsArticleDto::getId, article -> article));

            return articleIds.stream()
                    .map(id -> {
                        NewsClient.NewsArticleDto article = articleMap.get(id);
                        if (article == null) {
                            return null;
                        }
                        return Candidate.builder()
                                .articleId(article.getId())
                                .title(article.getTitle())
                                .url(article.getUrl())
                                .snippet(truncate(article.getContent(), 200))
                                .source(article.getSource())
                                .publishedAt(article.getPublishedAt() != null ? article.getPublishedAt() : null)
                                .imageUrl(article.getImageUrl())
                                .build();
                    })
                    .filter(candidate -> candidate != null)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[action-compose] 拉取候选详情失败，使用兜底候选|error={}", e.getMessage());
            return articleIds.stream()
                    .map(id -> Candidate.builder()
                            .articleId(id)
                            .title("Article " + id)
                            .snippet("Details unavailable")
                            .build())
                    .collect(Collectors.toList());
        }
    }

    /**
     * 根据答案中引用的新闻ID构建候选列表（包含标题和图片URL）
     */
    private List<Candidate> buildCandidatesByCitationIds(List<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            Map<Long, NewsClient.NewsArticleDto> articleMap = newsClient.getArticlesByIds(articleIds)
                    .stream()
                    .collect(Collectors.toMap(NewsClient.NewsArticleDto::getId, article -> article));

            return articleIds.stream()
                    .map(id -> {
                        NewsClient.NewsArticleDto article = articleMap.get(id);
                        if (article == null) {
                            log.warn("[action-compose] 引用ID对应文章不存在|id={}", id);
                            return null;
                        }
                        return Candidate.builder()
                                .articleId(article.getId())
                                .title(article.getTitle())
                                .url(article.getUrl())
                                .snippet(truncate(article.getContent(), 200))
                                .source(article.getSource())
                                .publishedAt(article.getPublishedAt() != null ? article.getPublishedAt() : null)
                                .imageUrl(article.getImageUrl())
                                .build();
                    })
                    .filter(candidate -> candidate != null)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[action-compose] 根据引用拉取候选详情失败，使用兜底候选|error={}", e.getMessage());
            return articleIds.stream()
                    .map(id -> Candidate.builder()
                            .articleId(id)
                            .title("Article " + id)
                            .snippet("Details unavailable")
                            .build())
                    .collect(Collectors.toList());
        }
    }

    private AgentResponse buildErrorResponse(String sessionId, String errorMessage) {
        return AgentResponse.builder()
                .sessionId(sessionId)
                .answer("内部错误: " + errorMessage)
                .candidates(new ArrayList<>())
                .timestamp(LocalDateTime.now())
                .build();
    }

    private String determinePipelineType(TaskFamily taskFamily) {
        if (taskFamily == null) {
            return "Unknown";
        }
        return switch (taskFamily) {
            case QA -> "QA";
            case SUMMARY, COMPARE, TIMELINE, DEEP_DIVE -> "Summary";
            case SEARCH, MONITORING -> "Search";
            default -> "Unknown";
        };
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private String readString(Map<String, Object> data, String key, String defaultValue) {
        if (data == null || key == null || !data.containsKey(key) || data.get(key) == null) {
            return defaultValue;
        }
        return String.valueOf(data.get(key));
    }

    private Integer readInteger(Map<String, Object> data, String key, Integer defaultValue) {
        if (data == null || key == null || !data.containsKey(key) || data.get(key) == null) {
            return defaultValue;
        }
        Object value = data.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignore) {
            return defaultValue;
        }
    }

    private Boolean readBoolean(Map<String, Object> data, String key, Boolean defaultValue) {
        if (data == null || key == null || !data.containsKey(key) || data.get(key) == null) {
            return defaultValue;
        }
        Object value = data.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private List<String> readStringList(Map<String, Object> data, String key) {
        if (data == null || key == null || !data.containsKey(key) || data.get(key) == null) {
            return List.of();
        }
        Object value = data.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> item == null ? "" : String.valueOf(item))
                .filter(item -> !item.isBlank())
                .collect(Collectors.toList());
    }
}
