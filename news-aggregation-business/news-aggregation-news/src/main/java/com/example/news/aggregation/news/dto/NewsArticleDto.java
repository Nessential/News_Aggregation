package com.example.news.aggregation.news.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 新闻文章 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsArticleDto {

    private Long id;
    private String title;
    private String url;
    private String content;
    private String source;
    private String publishedAt;
    private Long categoryId;
    private String categoryName;
}