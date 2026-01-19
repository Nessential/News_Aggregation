package com.example.news.aggregation.llm.springai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * MCP工具配置
 * 管理检索和重排工具的参数配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.llm.mcp.tool")
public class McpToolConfig {
    
    /** 检索工具配置 */
    private RetrievalConfig retrieval = new RetrievalConfig();
    
    /** 重排工具配置 */
    private RerankConfig rerank = new RerankConfig();
    
    /**
     * 检索工具配置项
     */
    @Data
    public static class RetrievalConfig {
        /** 默认召回数量 */
        private Integer defaultTopK = 10;
        
        /** 最大召回数量 */
        private Integer maxTopK = 50;
        
        /** 最小相似度阈值 */
        private Double minSimilarity = 0.5;
    }
    
    /**
     * 重排工具配置项
     */
    @Data
    public static class RerankConfig {
        /** 默认保留数量 */
        private Integer defaultTopN = 5;
        
        /** 最大保留数量 */
        private Integer maxTopN = 20;
        
        /** 是否启用多样性算法 */
        private Boolean diversityEnabled = true;
        
        /** 多样性权重 (0-1, 越大越重视多样性) */
        private Double diversityWeight = 0.3;
    }
}
