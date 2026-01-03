package com.example.news.aggregation.news.service;

/**
 * 翻译服务接口
 */
public interface TranslationService {

    /**
     * 执行待翻译新闻的翻译任务
     *
     * @param batchSize 每批处理数量
     */
    void translatePendingNews(int batchSize);
}