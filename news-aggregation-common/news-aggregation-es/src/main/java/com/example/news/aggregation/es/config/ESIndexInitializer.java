package com.example.news.aggregation.es.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * ES 索引初始化器
 * 应用启动时自动创建 news 索引
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ESIndexInitializer {

    private final ElasticsearchClient esClient;
    private final EsProperties esProperties;

    @PostConstruct
    public void init() {
        try {
            // 1. 健康检查
            if (!checkElasticsearchHealth()) {
                log.error("⚠️ ES 服务未启动或不可用，搜索功能将不可用");
                log.error("⚠️ 请检查 ES 连接配置：{}", "elasticsearch.yml");
                return;
            }
            
            // 2. 检查索引是否存在
            String indexName = esProperties.getIndexName();
            boolean exists = esClient.indices()
                    .exists(ExistsRequest.of(e -> e.index(indexName)))
                    .value();

            if (!exists) {
                // 创建索引
                createIndex();
                log.info("✅ ES 索引创建成功: {}", indexName);
            } else {
                log.info("✅ ES 索引已存在: {}", indexName);
            }

        } catch (Exception e) {
            log.error("❌ ES 索引初始化失败", e);
            // 不抛异常,允许应用继续启动
        }
    }
    
    /**
     * 检查 ES 健康状态
     */
    private boolean checkElasticsearchHealth() {
        try {
            var response = esClient.cluster().health();
            String status = response.status().jsonValue();
            
            log.info("ES 集群状态: {}", status);
            
            if ("red".equals(status)) {
                log.warn("⚠️ ES 集群状态为 RED，部分功能可能不可用");
            }
            
            return true;
            
        } catch (Exception e) {
            log.error("❌ ES 健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 创建索引
     */
    private void createIndex() throws IOException {
        // 读取 mapping 文件
        InputStream mappingStream = getClass()
                .getClassLoader()
                .getResourceAsStream("elasticsearch/news-index-mapping.json");

        if (mappingStream == null) {
            log.warn("未找到 mapping 文件,使用默认配置");
            createDefaultIndex();
            return;
        }

        // 使用 JSON 文件创建索引
        esClient.indices().create(CreateIndexRequest.of(c -> c
                .index(esProperties.getIndexName())
                .withJson(mappingStream)
        ));
    }

    /**
     * 创建默认索引(简单版)
     */
    private void createDefaultIndex() throws IOException {
        esClient.indices().create(c -> c
                .index(esProperties.getIndexName())
                .settings(s -> s
                        .numberOfShards("3")
                        .numberOfReplicas("1")
                )
        );
    }
}