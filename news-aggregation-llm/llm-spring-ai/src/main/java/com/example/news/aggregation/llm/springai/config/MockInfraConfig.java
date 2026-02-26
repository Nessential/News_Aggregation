package com.example.news.aggregation.llm.springai.config;

import com.example.news.aggregation.embedding.service.EmbeddingService;
import com.example.news.aggregation.es.service.ElasticsearchService;
import com.example.news.aggregation.vector.model.SearchResult;
import com.example.news.aggregation.vector.model.VectorPoint;
import com.example.news.aggregation.vector.service.VectorStoreService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Configuration
@ConditionalOnProperty(name = "app.llm.mock-mode", havingValue = "true")
public class MockInfraConfig {

    @Bean
    @ConditionalOnMissingBean
    public ElasticsearchService elasticsearchService() {
        return new NoopElasticsearchService();
    }

    @Bean
    @ConditionalOnMissingBean
    public VectorStoreService vectorStoreService() {
        return new NoopVectorStoreService();
    }

    @Bean
    @ConditionalOnMissingBean
    public EmbeddingService embeddingService() {
        return new NoopEmbeddingService();
    }

    private static final class NoopElasticsearchService implements ElasticsearchService {
        @Override
        public void indexDocument(String indexName, String docId, Map<String, Object> document) {
        }

        @Override
        public void deleteDocument(String indexName, String docId) {
        }

        @Override
        public void bulkIndex(String indexName, Map<String, Map<String, Object>> documents) {
        }

        @Override
        public List<Map<String, Object>> search(String indexName, String query, int topK, List<String> fields) {
            return new ArrayList<>();
        }

        @Override
        public List<Map<String, Object>> search(String indexName,
                                                String query,
                                                int topK,
                                                List<String> fields,
                                                Map<String, Object> filters,
                                                String sortBy) {
            return new ArrayList<>();
        }
    }

    private static final class NoopVectorStoreService implements VectorStoreService {
        @Override
        public void ensureCollection(String collectionName, int vectorSize) {
        }

        @Override
        public void upsert(String collectionName, List<VectorPoint> points) {
        }

        @Override
        public List<SearchResult> search(String collectionName, float[] queryVector, int topK, Map<String, Object> filter) {
            return Collections.emptyList();
        }

        @Override
        public void delete(String collectionName, List<String> ids) {
        }

        @Override
        public boolean exists(String collectionName, String id) {
            return false;
        }

        @Override
        public long count(String collectionName) {
            return 0L;
        }
    }

    private static final class NoopEmbeddingService implements EmbeddingService {
        @Override
        public float[] embed(String text) {
            return new float[0];
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            if (texts == null || texts.isEmpty()) {
                return Collections.emptyList();
            }
            List<float[]> results = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                results.add(new float[0]);
            }
            return results;
        }

        @Override
        public int getDimension() {
            return 0;
        }
    }
}
