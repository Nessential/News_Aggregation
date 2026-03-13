package com.example.news.aggregation.rpc.contract.news;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 *  DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsArticleDto implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long id;
    private String title;
    private String url;
    private String content;
    private String source;
    private String publishedAt;
    private String imageUrl;
}
