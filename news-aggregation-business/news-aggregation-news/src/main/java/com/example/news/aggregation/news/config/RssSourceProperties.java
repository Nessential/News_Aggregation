package com.example.news.aggregation.news.config;


import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "news.rss")
public class RssSourceProperties {

    private List<RssSource> sources;
    /**
     * URL 过滤关键词，命中则跳过抓取（不入库）
     */
    private List<String> skipUrlKeywords;

    @Getter
    @Setter
    public static class RssSource {
        /**
         * 源名称
         */
        private String name;

        /**
         * RSS 地址
         */
        private String url;

        /**
         * 分类
         */
        private String category;
    }
}
