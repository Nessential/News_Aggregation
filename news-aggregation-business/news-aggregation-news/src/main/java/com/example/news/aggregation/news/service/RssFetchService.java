package com.example.news.aggregation.news.service;


/**
 * RSS 抓取服务接口
 *
 * @author NewsAggregation
 */
public interface RssFetchService {

    /**
     * 从所有配置的 RSS 源抓取新闻并保存
     */
    void fetchAndSaveNews();

    /**
     * 获取待翻译的新闻
     */
    void selectForTranslate();

}
