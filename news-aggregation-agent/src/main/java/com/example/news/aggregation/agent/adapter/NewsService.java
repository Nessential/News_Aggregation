package com.example.news.aggregation.agent.adapter;

import java.util.List;

/**
 * NewsService适配器接口
 * 临时接口，用于Agent模块访问新闻数据
 * 
 * TODO: 后续应该从news模块引入真实的NewsService
 */
public interface NewsService {
    
    /**
     * 批量查询文章
     * @param articleIds 文章ID列表
     * @return 文章列表
     */
    List<NewsArticle> getArticlesByIds(List<Long> articleIds);
}
