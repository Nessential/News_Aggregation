package com.example.news.aggregation.es.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch 配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "elasticsearch")
public class EsProperties {

    /**
     * ES 服务器主机地址
     */
    private String host = "localhost";

    /**
     * ES 服务器端口
     */
    private int port = 9200;

    /**
     * 用户名
     */
    private String username = "";

    /**
     * 密码
     */
    private String password = "";

    /**
     * 索引名称（默认：news）
     */
    private String indexName = "news";
}
