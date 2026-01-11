package com.example.news.aggregation.news.service.impl;

import com.example.news.aggregation.news.domain.entity.News;
import com.example.news.aggregation.news.infrastructure.mapper.NewsMapper;
import com.example.news.aggregation.news.infrastructure.story.StoryMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 新闻向量化Pipeline编排器
 *
 * 流程：MySQL → Topic向量 → Chunk向量 → Story归簇
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsVectorPipeline {




    private final StoryMatcher storyMatcher;
    private final TopicVectorService topicVectorService;
    private final NewsMapper newsMapper;
    private final ChunkVectorService chunkVectorService;
    @Transactional(rollbackFor = Exception.class)
    public void processNews(News news){
        try{
            log.info("开始新闻向量化: newsId={}}",news.getId());
            // 1. Story 归簇（StoryMatcher 会自己生成临时 embedding）
            String canonicalId = storyMatcher.findOrCreateCanonicalId(news);
            news.setCanonical_id(canonicalId);
            news.setCanonical_status(1);
            // 2. 生成 Topic 向量（此时已有 canonical_id）
            generateTopicVectors(news);

            // 3. 生成 Chunk 向量（此时已有 canonical_id）
            generateChunkVectors(news);

            news.setVector_status(1);
            newsMapper.updateById(news);

            log.info("新闻处理完成: newsId={}, canonical={}",
                    news.getId(), canonicalId);
        } catch (Exception e) {
            log.error("新闻处理失败: newsId={}", news.getId(), e);
            news.setVector_status(2);
            news.setCanonical_status(2);
            newsMapper.updateById(news);
            throw e;
        }

    }

    /**
     * 生成 Topic 向量
     */
    private void generateTopicVectors(News news) {
        if (hasEnglishContent(news)) {
            topicVectorService.vectorizeTopicEn(news);
        }
//        if (hasChineseContent(news)) {
//            topicVectorService.vectorizeTopicZh(news);
//        }

    }

    /**
     * 生成 Chunk 向量
     */
    private void generateChunkVectors(News news) {
        if (hasEnglishContent(news)) {
            chunkVectorService.vectorizeChunksEn(news);
        }
        if (hasChineseContent(news)) {
            chunkVectorService.vectorizeChunksZh(news);
        }
    }




    private boolean hasEnglishContent(News news) {
        return isNotEmpty(news.getTitle()) || isNotEmpty(news.getContext());
    }

    private boolean hasChineseContent(News news) {
        return news.getTranslation_status() != null && news.getTranslation_status() == 1
                && (isNotEmpty(news.getTitle_cn()) || isNotEmpty(news.getContext_cn()));
    }

    private boolean isNotEmpty(String str) {
        return str != null && !str.trim().isEmpty();
    }
}
