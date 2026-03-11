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

    /** RSS 源列表 */
    private List<RssSource> sources;

    /** URL 过滤关键词，命中后跳过抓取 */
    private List<String> skipUrlKeywords;

    @Getter
    @Setter
    public static class RssSource {
        /** 源名称 */
        private String name;

        /** RSS 地址 */
        private String url;

        /** 分类ID */
        private Long categoryId;

        /** 分类名称 */
        private String category;

        /** 当前源的默认图片 URL（当条目无图时使用） */
        private String defaultImageUrl;

        /** 当前源单次抓取条数上限（为空或<=0表示不限制） */
        private Integer maxItems;
    }
}
