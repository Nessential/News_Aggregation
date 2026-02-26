package com.example.news.aggregation.news.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 首页新闻列表响应
 */
@Data
@Builder
public class NewsListResponse {

    private long total;
    private int page;
    private int pageSize;
    private List<NewsListItemDto> items;
}
