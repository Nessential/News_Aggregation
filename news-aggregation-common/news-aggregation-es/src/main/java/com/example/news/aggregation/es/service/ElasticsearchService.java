package com.example.news.aggregation.es.service;

import java.util.Map;

/**
 * Elasticsearch 服务接口
 */
public interface ElasticsearchService {

    /**
     * 索引文档到 ES
     * @param indexName 索引名称
     * @param docId 文档ID
     * @param document 文档内容
     */
    void indexDocument(String indexName, String docId, Map<String, Object> document);

    /**
     * 从 ES 删除文档
     * @param indexName 索引名称
     * @param docId 文档ID
     */
    void deleteDocument(String indexName, String docId);

    /**
     * 批量索引文档
     * @param indexName 索引名称
     * @param documents 文档映射 (docId -> document)
     */
    void bulkIndex(String indexName, Map<String, Map<String, Object>> documents);
}