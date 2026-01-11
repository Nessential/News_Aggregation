package com.example.news.aggregation.news.service.impl;

import com.example.news.aggregation.news.domain.entity.News;
import com.example.news.aggregation.news.infrastructure.mapper.NewsMapper;
import com.example.news.aggregation.news.service.NewsVectorService;
import com.example.news.aggregation.vector.service.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsVectorServiceImpl implements NewsVectorService {

    private final NewsVectorPipeline pipeline;
    private final NewsMapper newsMapper;
    private final VectorStoreService vectorStoreService;

    private static final String COLLECTION_TOPIC_EN = "news_topic_en";
    private static final String COLLECTION_TOPIC_ZH = "news_topic_zh";
    private static final String COLLECTION_CHUNKS_EN = "news_chunks_en";
    private static final String COLLECTION_CHUNKS_ZH = "news_chunks_zh";

    @Override
    public void vectorizeNews(News news) {
        pipeline.processNews(news);
    }

    @Override
    public int vectorizeBatch(List<News> newsList) {
        int success = 0;
        for (News news : newsList) {
            try {
                vectorizeNews(news);
                success++;
            } catch (Exception e) {
                log.error("批量向量化失败: newsId={}", news.getId(), e);
            }
        }
        return success;
    }

    @Override
    public int vectorizePendingNews(int batchSize) {
        List<News> pendingNews = newsMapper.selectForVectorization(batchSize);

        if (pendingNews.isEmpty()) {
            log.info("没有待向量化的新闻");
            return 0;
        }

        log.info("开始向量化 {} 条新闻", pendingNews.size());
        return vectorizeBatch(pendingNews);
    }

    @Override
    public void deleteNewsVectors(Long newsId) {
        try {
            // 删除 Topic 向量
            String topicIdEn = "topic_" + newsId + "_en";
            String topicIdZh = "topic_" + newsId + "_zh";
            vectorStoreService.delete(COLLECTION_TOPIC_EN, List.of(topicIdEn));
            vectorStoreService.delete(COLLECTION_TOPIC_ZH, List.of(topicIdZh));

            // 删除 Chunk 向量（假设最多10个chunk）
            List<String> chunkIdsEn = generateChunkIds(newsId, "en", 10);
            vectorStoreService.delete(COLLECTION_CHUNKS_EN, chunkIdsEn);

            List<String> chunkIdsZh = generateChunkIds(newsId, "zh", 10);
            vectorStoreService.delete(COLLECTION_CHUNKS_ZH, chunkIdsZh);

            log.info("删除新闻向量: newsId={}", newsId);
        } catch (Exception e) {
            log.error("删除新闻向量失败: newsId={}", newsId, e);
            throw e;
        }
    }

    @Override
    public boolean isVectorized(Long newsId) {
        try {
            String topicId = "topic_" + newsId + "_en";
            return vectorStoreService.exists(COLLECTION_TOPIC_EN, topicId);
        } catch (Exception e) {
            log.warn("检查向量化状态失败: newsId={}", newsId, e);
            return false;
        }
    }

    private List<String> generateChunkIds(Long newsId, String language, int maxChunks) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < maxChunks; i++) {
            ids.add(newsId + "_" + language + "_chunk_" + i);
        }
        return ids;
    }
}