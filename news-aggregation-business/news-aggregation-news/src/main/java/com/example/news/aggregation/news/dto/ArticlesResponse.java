package com.example.news.aggregation.news.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量查询响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticlesResponse {
    // 文章列表
    private List<NewsArticleDto> articles;
}
