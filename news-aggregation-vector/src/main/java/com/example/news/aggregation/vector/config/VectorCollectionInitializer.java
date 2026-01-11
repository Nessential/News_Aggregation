package com.example.news.aggregation.vector.config;

import com.example.news.aggregation.vector.service.VectorStoreService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class VectorCollectionInitializer {

    private final VectorStoreService vectorStoreService;

    private static final int VECTOR_SIZE = 2048;

    @PostConstruct
    public void init() {
        log.info("初始化向量集合...");

        try {
            // Topic 向量集合（只初始化英文，用于 Story 归簾）
            vectorStoreService.ensureCollection("news_topic_en", VECTOR_SIZE);
            log.info("英文 Topic 集合初始化完成: news_topic_en");

            // Chunk 向量集合
            vectorStoreService.ensureCollection("news_chunks_en", VECTOR_SIZE);
            log.info("英文 Chunk 集合初始化完成: news_chunks_en");

            vectorStoreService.ensureCollection("news_chunks_zh", VECTOR_SIZE);
            log.info("中文 Chunk 集合初始化完成: news_chunks_zh");

        } catch (Exception e) {
            log.error("向量集合初始化失败", e);
            throw new RuntimeException("向量集合初始化失败", e);
        }
    }
}