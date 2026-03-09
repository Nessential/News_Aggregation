package com.example.news.aggregation.news.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 新闻详情 DTO。
 */
@Data
@Builder
public class NewsDetailDto {

    private Long id;
    private String title;
    private String summary;
    private String content;
    private String titleCn;
    private String summaryCn;
    private String contentCn;
    private String titleEn;
    private String summaryEn;
    private String contentEn;
    private String imageUrl;
    private String link;
    private String source;
    private String publishedAt;
    private Long publicationTime;
    private Long categoryId;
    private String categoryName;
}