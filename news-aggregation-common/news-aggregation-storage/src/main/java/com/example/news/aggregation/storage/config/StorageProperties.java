package com.example.news.aggregation.storage.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
/**
 * 存储服务配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "storage.minio")
public class StorageProperties {

    /**
     * MinIO 服务地址
     */
    private String endpoint = "http://192.168.1.10:9002";

    /**
     * 访问密钥
     */
    private String accessKey = "admin";

    /**
     * 秘密密钥
     */
    private String secretKey = "12345678";

    /**
     * 默认存储桶
     */
    private String bucket = "news-image";

    /**
     * 连接超时时间（毫秒）
     */
    private long connectTimeout = 10000;

    /**
     * 读取超时时间（毫秒）
     */
    private long readTimeout = 30000;
}
