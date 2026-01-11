package com.example.news.aggregation.news.service;

import com.example.news.aggregation.news.domain.entity.News;

import java.util.List;

public interface NewsVectorService {

    /**
     * 向量化单条新闻（中英文双语）
     */
    void vectorizeNews(News news);

    /**
     * 批量向量化新闻
     * @return 成功向量化的数量
     */
    int vectorizeBatch(List<News> newsList);

    /**
     * 向量化待处理的新闻（定时任务调用）
     * @param batchSize 批次大小
     * @return 成功向量化的数量
     */
    int vectorizePendingNews(int batchSize);

    /**
     * 删除新闻的向量数据
     */
    void deleteNewsVectors(Long newsId);

    /**
     * 检查新闻是否已向量化
     */
    boolean isVectorized(Long newsId);
}