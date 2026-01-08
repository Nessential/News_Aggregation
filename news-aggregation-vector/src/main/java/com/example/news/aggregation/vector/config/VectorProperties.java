package com.example.news.aggregation.vector.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 向量数据库配置属性
 * 
 * @author system
 */
@Data
@Component
@ConfigurationProperties(prefix = "vector.qdrant")
public class VectorProperties {
    
    /**
     * Qdrant服务器主机地址
     */
    private String host = "localhost";
    
    /**
     * Qdrant服务器端口
     */
    private int port = 6334;
    
    /**
     * 是否使用TLS连接
     */
    private boolean useTls = false;
    
    /**
     * 默认集合名称
     */
    private String collectionName = "news_vectors";
    
    /**
     * 向量维度大小
     */
    private int vectorSize = 2048;
    
    /**
     * API密钥（可选）
     */
    private String apiKey;
}