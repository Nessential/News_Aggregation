package com.example.news.aggregation.vector.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Qdrant客户端配置类
 * 负责创建和管理Qdrant数据库连接
 * 
 * @author system
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class QdrantClientConfig {

    private final VectorProperties properties;
    private QdrantClient qdrantClient;

    /**
     * 创建Qdrant客户端Bean
     * 
     * @return QdrantClient实例
     */
    @Bean
    public QdrantClient qdrantClient() {
        log.info("初始化 Qdrant 客户端:");
        log.info("  host: {}", properties.getHost());
        log.info("  port: {}", properties.getPort());
        log.info("  useTls: {}", properties.isUseTls());
        log.info("  collectionName: {}", properties.getCollectionName());

        // 构建Qdrant gRPC客户端
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(
                properties.getHost(),
                properties.getPort(),
                properties.isUseTls()
        );

        // 如果配置了API密钥，则添加认证
        if (properties.getApiKey() != null && !properties.getApiKey().isEmpty()) {
            builder.withApiKey(properties.getApiKey());
        }

        this.qdrantClient = new QdrantClient(builder.build());
        return this.qdrantClient;
    }

    /**
     * 应用关闭时清理资源
     */
    @PreDestroy
    public void destroy() {
        if (qdrantClient != null) {
            try {
                qdrantClient.close();
                log.info("Qdrant 客户端已关闭");
            } catch (Exception e) {
                log.error("关闭 Qdrant 客户端失败", e);
            }
        }
    }
}