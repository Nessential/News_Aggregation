package com.example.news.aggregation.agent.service;

import com.example.news.aggregation.agent.domain.AgentResponse;
import com.example.news.aggregation.agent.domain.Candidate;

import java.util.List;

/**
 * Utilities for generating markdown output for frontend rendering.
 */
public final class MarkdownResponseFormatter {

    private MarkdownResponseFormatter() {
    }

    public static String formatAnswer(List<AgentResponse.AnswerItemView> answerItems, String fallbackText) {
        if (answerItems == null || answerItems.isEmpty()) {
            return formatPlainText(fallbackText);
        }

        StringBuilder sb = new StringBuilder();
        for (AgentResponse.AnswerItemView item : answerItems) {
            if (item == null || isBlank(item.getText())) {
                continue;
            }

            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(item.getText().trim());
            appendRelatedNews(sb, item.getRelatedNews());
        }
        if (sb.length() == 0) {
            return formatPlainText(fallbackText);
        }
        return sb.toString();
    }

    public static String formatPlainText(String text) {
        if (isBlank(text)) {
            return "";
        }
        return text.trim();
    }

    private static void appendRelatedNews(StringBuilder sb, List<Candidate> relatedNews) {
        if (relatedNews == null || relatedNews.isEmpty()) {
            return;
        }
        sb.append("\n\n#### 相关新闻\n");
        for (Candidate news : relatedNews) {
            if (news == null) {
                continue;
            }
            String title = isBlank(news.getTitle()) ? ("新闻 " + safeId(news.getArticleId())) : news.getTitle().trim();
            String link = isBlank(news.getUrl()) ? null : news.getUrl().trim();
            String source = isBlank(news.getSource()) ? "" : news.getSource().trim();
            String publishedAt = isBlank(news.getPublishedAt()) ? "" : news.getPublishedAt().trim();

            if (!isBlank(link)) {
                sb.append("- [").append(title).append("](").append(link).append(")");
            } else {
                sb.append("- ").append(title);
            }

            if (!isBlank(source) || !isBlank(publishedAt)) {
                sb.append("（");
                if (!isBlank(source)) {
                    sb.append(source);
                }
                if (!isBlank(source) && !isBlank(publishedAt)) {
                    sb.append(" · ");
                }
                if (!isBlank(publishedAt)) {
                    sb.append(publishedAt);
                }
                sb.append("）");
            }
            sb.append("\n");
        }
    }

    private static String safeId(Long id) {
        return id == null ? "未知" : String.valueOf(id);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
