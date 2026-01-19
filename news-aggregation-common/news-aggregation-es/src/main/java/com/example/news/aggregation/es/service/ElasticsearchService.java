package com.example.news.aggregation.es.service;

import java.util.List;
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
    
    /**
     * 搜索文档
     * @param indexName 索引名称
     * @param query 查询字符串
     * @param topK 返回结果数量
     * @param fields 要搜索的字段列表
     * @return 搜索结果列表，每个结果包含_id、_score和_source
     */
    List<Map<String, Object>> search(String indexName, String query, int topK, List<String> fields);
}
