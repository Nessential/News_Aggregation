package com.example.news.aggregation.embedding.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "embedding.zhipu")
public class EmbeddingProperties {
    private String apiKey = "";
    private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";
    private String model = "embedding-3";
    private int dimensions = 2048;
    private long connectTimeout = 10000;
    private long readTimeout = 30000;
    private int chunkSize = 500;
    private int chunkOverlap = 50;
    private int minChunkSize = 100;
}