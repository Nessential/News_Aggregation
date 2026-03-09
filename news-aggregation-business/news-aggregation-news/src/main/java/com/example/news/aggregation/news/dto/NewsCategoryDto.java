package com.example.news.aggregation.news.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 新闻分类 DTO。
 */
@Data
@Builder
public class NewsCategoryDto {
    private Long id;
    private String name;
}

