package com.example.news.aggregation.agent.tool.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 检索结果 DTO。
 * 封装 Tool 执行返回的检索结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalResult {

    /** 文章 ID */
    private Long articleId;

    /** 相关性得分 */
    private Double score;

    /** 匹配的内容片段 */
    private String matchedSnippet;

    /** 完整内容（可选） */
    private String fullContent;

    /** 元数据 */
    private String metadata;

    /**
     * 批量创建结果。
     */
    public static List<RetrievalResult> from(List<Long> articleIds, List<Double> scores) {
        if (articleIds == null || scores == null || articleIds.size() != scores.size()) {
            throw new IllegalArgumentException("Article IDs and scores must have the same size");
        }

        return java.util.stream.IntStream.range(0, articleIds.size())
                .mapToObj(i -> RetrievalResult.builder()
                        .articleId(articleIds.get(i))
                        .score(scores.get(i))
                        .build())
                .toList();
    }
}