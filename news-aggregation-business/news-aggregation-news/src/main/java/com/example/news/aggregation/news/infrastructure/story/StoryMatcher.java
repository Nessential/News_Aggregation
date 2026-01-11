package com.example.news.aggregation.news.infrastructure.story;


import com.example.news.aggregation.embedding.service.EmbeddingService;
import com.example.news.aggregation.news.domain.entity.News;
import com.example.news.aggregation.vector.model.SearchResult;
import com.example.news.aggregation.vector.service.VectorStoreService;
import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Service
public class StoryMatcher {

    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;

    private static final String COLLECTION_TOPIC_EN = "news_topic_en";
    @Value("${story.time-window-days:7}")
    private int timeWindowDays;

    @Value("${story.similarity-threshold:0.90}")
    private float similarityThreshold;

    /**
     * 查找或创建canonical_id
     * @param news 新闻实体
     * @return canonical_id
     */
    public String findOrCreateCanonicalId(News news){
        String topicText = buildTopicText(news);

        if (topicText == null || topicText.isEmpty()) {
            return createNewCanonicalId(news);
        }

        float[] embedding = embeddingService.embed(topicText);

        long timeWindowMs = timeWindowDays * 24 * 3600 * 1000L;
        long minPublishTime = news.getPublication_time() - timeWindowMs;

        Map<String, Object> filter = new HashMap<>();
        try {
            List<SearchResult> results = vectorStoreService.search(
                    COLLECTION_TOPIC_EN,
                    embedding,
                    10,
                    filter
            );

            for (SearchResult result : results) {
                Long publishedAt = (Long) result.getPayload().get("published_at");
                if (publishedAt == null || publishedAt < minPublishTime) {
                    continue;
                }

                if (result.getScore() >= similarityThreshold) {
                    String existingCanonicalId = (String) result.getPayload().get("canonical_id");
                    if (existingCanonicalId != null && !existingCanonicalId.isEmpty()) {
                        log.info("找到相似Story: newsId={}, canonical={}, score={}",
                                news.getId(), existingCanonicalId, result.getScore());
                        return existingCanonicalId;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Story 匹配失败，创建新 Story: newsId={}", news.getId(), e);
        }

        return createNewCanonicalId(news);


    }

    private String createNewCanonicalId(News news) {
        String canonicalId = "story_" + news.getId();
        log.info("创建新Story: newsId={}, canonical_id={}", news.getId(), canonicalId);
        return canonicalId;
    }


    /**
     * 构建用于匹配的文本
     */
    private String buildTopicText(News news) {
        StringBuilder sb = new StringBuilder();

        // 优先使用英文（更稳定）
        if (news.getTitle() != null && !news.getTitle().isEmpty()) {
            sb.append(news.getTitle());
        } else if (news.getTitle_cn() != null && !news.getTitle_cn().isEmpty()) {
            sb.append(news.getTitle_cn());
        }

        // 加上摘要
        if (news.getSummary() != null && !news.getSummary().isEmpty()) {
//            加空格避免单词粘连
            sb.append(" ").append(news.getSummary());
        } else if (news.getSummary_cn() != null && !news.getSummary_cn().isEmpty()) {
            sb.append(" ").append(news.getSummary_cn());
        }

        return sb.toString().trim();
    }


}
