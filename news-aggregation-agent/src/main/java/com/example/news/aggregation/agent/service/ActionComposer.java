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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        log.info("[action-compose] start build response|sessionId={} |taskFamily={}", sessionId, taskFamily);

        try {
            List<PipelineResult.PipelineAnswerItem> pipelineItems = pipelineResult.getAnswerItems();
            List<AgentResponse.AnswerItemView> answerItems = buildAnswerItems(pipelineItems);
            String mergedAnswer = answerItems.stream()
                    .map(AgentResponse.AnswerItemView::getText)
                    .filter(text -> text != null && !text.isBlank())
                    .collect(Collectors.joining("\n"));

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
                    .answer(mergedAnswer)
                    .answerItems(answerItems)
                    .taskFamily(taskFamily)
                    .needsClarification(false)
                    .timestamp(LocalDateTime.now())
                    .executionTimeMs(pipelineResult.getExecutionTimeMs())
                    .metadata(metadata)
                    .build();
        } catch (Exception e) {
            log.error("[action-compose] build response failed", e);
            return buildErrorResponse(sessionId, e.getMessage());
        }
    }

    private List<AgentResponse.AnswerItemView> buildAnswerItems(List<PipelineResult.PipelineAnswerItem> pipelineItems) {
        if (pipelineItems == null || pipelineItems.isEmpty()) {
            return List.of();
        }

        List<Long> allNewsIds = pipelineItems.stream()
                .flatMap(item -> item.getNewsIds() == null ? java.util.stream.Stream.<Long>empty() : item.getNewsIds().stream())
                .filter(id -> id != null)
                .distinct()
                .toList();

        Map<Long, NewsClient.NewsArticleDto> articleMap = new LinkedHashMap<>();
        if (!allNewsIds.isEmpty()) {
            try {
                articleMap = newsClient.getArticlesByIds(allNewsIds).stream()
                        .filter(article -> article != null && article.getId() != null)
                        .collect(Collectors.toMap(NewsClient.NewsArticleDto::getId, article -> article, (a, b) -> a, LinkedHashMap::new));
            } catch (Exception e) {
                log.warn("[action-compose] load related news failed, fallback minimal cards|error={}", e.getMessage());
            }
        }
        final Map<Long, NewsClient.NewsArticleDto> finalArticleMap = articleMap;

        List<AgentResponse.AnswerItemView> result = new ArrayList<>();
        for (PipelineResult.PipelineAnswerItem item : pipelineItems) {
            if (item == null || item.getText() == null || item.getText().isBlank()) {
                continue;
            }
            List<Long> newsIds = item.getNewsIds() == null ? List.of() : item.getNewsIds();
            List<Candidate> relatedNews = newsIds.stream()
                    .map(id -> toCandidateCard(id, finalArticleMap.get(id)))
                    .filter(card -> card != null)
                    .toList();
            result.add(AgentResponse.AnswerItemView.builder()
                    .text(item.getText())
                    .newsIds(newsIds)
                    .relatedNews(relatedNews)
                    .build());
        }
        return result;
    }

    private Candidate toCandidateCard(Long id, NewsClient.NewsArticleDto article) {
        if (id == null) {
            return null;
        }
        if (article == null) {
            return Candidate.builder()
                    .articleId(id)
                    .title("Article " + id)
                    .snippet("Details unavailable")
                    .build();
        }
        return Candidate.builder()
                .articleId(article.getId())
                .title(article.getTitle())
                .url(article.getUrl())
                .snippet(truncate(article.getContent(), 200))
                .source(article.getSource())
                .publishedAt(article.getPublishedAt())
                .imageUrl(article.getImageUrl())
                .build();
    }

    private AgentResponse buildErrorResponse(String sessionId, String errorMessage) {
        return AgentResponse.builder()
                .sessionId(sessionId)
                .answer("内部错误: " + errorMessage)
                .answerItems(List.of())
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
