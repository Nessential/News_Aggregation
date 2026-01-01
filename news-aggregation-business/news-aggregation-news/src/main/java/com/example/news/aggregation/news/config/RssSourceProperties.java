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
