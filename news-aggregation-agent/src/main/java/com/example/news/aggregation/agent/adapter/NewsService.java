package com.example.news.aggregation.agent.adapter;

import java.util.List;

/**
 * NewsService 适配器接口。
 * 临时接口，用于 Agent 模块访问新闻数据。
 *
 * TODO: 后续应该从 news 模块引入真实的 NewsService。
 */
public interface NewsService {

    /**
     * 批量查询文章。
     *
     * @param articleIds 文章 ID 列表
     * @return 文章列表
     */
    List<NewsArticle> getArticlesByIds(List<Long> articleIds);
}