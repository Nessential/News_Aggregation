package com.example.news.aggregation.es.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;

import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.news.aggregation.es.exception.EsErrorCode;
import com.example.news.aggregation.es.exception.EsException;
import com.example.news.aggregation.es.service.ElasticsearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElasticsearchServiceImpl implements ElasticsearchService {

    private final ElasticsearchClient esClient;

    @Override
    public void indexDocument(String indexName, String docId, Map<String, Object> document) {
        try {
            IndexRequest<Map<String, Object>> request = IndexRequest.of(i -> i
                    .index(indexName)
                    .id(docId)
                    .document(document)
            );

            IndexResponse response = esClient.index(request);

            log.info("ES 索引完成: index={}, docId={}, result={}",
                    indexName, docId, response.result());

        } catch (Exception e) {
            log.error("ES 索引失败: index={}, docId={}", indexName, docId, e);
            throw new EsException("ES 索引失败: " + e.getMessage(), e, EsErrorCode.INDEX_DOCUMENT_FAILED);
        }
    }

    @Override
    public void deleteDocument(String indexName, String docId) {
        try {
            DeleteRequest request = DeleteRequest.of(d -> d
                    .index(indexName)
                    .id(docId)
            );

            esClient.delete(request);

            log.debug("ES 删除完成: index={}, docId={}", indexName, docId);

        } catch (Exception e) {
            log.error("ES 删除失败: index={}, docId={}", indexName, docId, e);
            throw new EsException("ES 删除失败: " + e.getMessage(), e, EsErrorCode.DELETE_DOCUMENT_FAILED);
        }
    }

    @Override
    public void bulkIndex(String indexName, Map<String, Map<String, Object>> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }

        try {
            List<BulkOperation> operations = new ArrayList<>();

            for (Map.Entry<String, Map<String, Object>> entry : documents.entrySet()) {
                String docId = entry.getKey();
                Map<String, Object> document = entry.getValue();

                BulkOperation operation = BulkOperation.of(b -> b
                        .index(idx -> idx
                                .index(indexName)
                                .id(docId)
                                .document(document)
                        )
                );

                operations.add(operation);
            }

            BulkRequest bulkRequest = BulkRequest.of(b -> b
                    .operations(operations)
            );

            BulkResponse response = esClient.bulk(bulkRequest);

            if (response.errors()) {
                log.warn("ES 批量索引部分失败: index={}, total={}, failed={}",
                        indexName, documents.size(), response.items().size());
            } else {
                log.debug("ES 批量索引完成: index={}, count={}",
                        indexName, documents.size());
            }

        } catch (Exception e) {
            log.error("ES 批量索引失败: index={}", indexName, e);
            throw new EsException("ES 批量索引失败: " + e.getMessage(), e, EsErrorCode.BULK_INDEX_FAILED);
        }
    }

    @Override
    public List<Map<String, Object>> search(String indexName, String query, int topK, List<String> fields) {
        if (query == null || query.trim().isEmpty()) {
            log.warn("ES 搜索查询为空: index={}", indexName);
            return new ArrayList<>();
        }
        
        try {
            /* 构建multi_match查询 */
            SearchRequest searchRequest = SearchRequest.of(s -> s
                .index(indexName)
                .size(topK)
                .query(q -> q
                    .multiMatch(m -> m
                        .query(query)
                        .fields(fields)
                    )
                )
            );
            
            // 执行搜索
            SearchResponse<Map> response = esClient.search(searchRequest, Map.class);
            
            // 解析结果
            List<Map<String, Object>> results = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> result = new java.util.HashMap<>();
                result.put("_id", hit.id());
                result.put("_score", hit.score());
                result.put("_source", hit.source());
                results.add(result);
            }
            
            log.debug("ES 搜索完成: index={}, query={}, results={}", 
                    indexName, query, results.size());
            return results;
            
        } catch (Exception e) {
            log.error("ES 搜索失败: index={}, query={}", indexName, query, e);
            // 优雅降级：返回空列表而不是抛出异常
            return new ArrayList<>();
        }
    }
}
