package com.example.news.aggregation.agent.adapter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * NewsArticle适配器实体
 * 临时实体，用于Agent模块
 * 
 * TODO: 后续应该从news模块引入真实的NewsArticle
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsArticle {
    
    /**
     * 文章ID
     */
    private Long id;
    
    /**
     * 标题
     */
    private String title;
    
    /**
     * 内容
     */
    private String content;
    
    /**
     * URL
     */
    private String url;
    
    /**
     * 来源
     */
    private String source;
    
    /**
     * 发布时间
     */
    private LocalDateTime publishedAt;
}
