package com.example.news.aggregation.news.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 首页新闻列表项 DTO。
 */
@Data
@Builder
public class NewsListItemDto {

    private Long id;
    private String title;
    private String summary;
    private String titleCn;
    private String summaryCn;
    private String titleEn;
    private String summaryEn;
    private String imageUrl;
    private String link;
    private String source;
    private String publishedAt;
    private Long publicationTime;
    private Long categoryId;
    private String categoryName;
}