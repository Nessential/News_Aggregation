package com.example.news.aggregation.news.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 新闻详情 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsArticleDto {
    // 文章ID
    private Long id;
    // 标题
    private String title;
    // 原文链接
    private String url;
    // 正文内容
    private String content;
    // 来源
    private String source;
    // 发布时间（字符串）
    private String publishedAt;
}
