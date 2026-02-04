package com.example.news.aggregation.agent.enums;

/**
 * 检索模式枚举
 * 定义不同的文档检索策略
 */
public enum RetrievalMode {
    /**
     * 向量检索 - 语义相似度
     */
    VECTOR,
    
    /**
     * 关键词检索 - BM25精确匹配
     */
    KEYWORD,
    
    /**
     * 混合检索 - Vector + Keyword RRF融合
     */
    HYBRID
}
