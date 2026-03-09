package com.example.news.aggregation.news.service.impl;

import com.example.news.aggregation.embedding.service.EmbeddingService;
import com.example.news.aggregation.news.domain.entity.News;
import com.example.news.aggregation.vector.model.VectorPoint;
import com.example.news.aggregation.vector.service.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Topic 向量生成服务，用于 Story 聚合与事件匹配。
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TopicVectorService {

    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;

    private static final String COLLECTION_TOPIC_EN = "news_topic_en";
    private static final String COLLECTION_TOPIC_ZH = "news_topic_zh";

    public void vectorizeTopicEn(News news) {
        String topicText = buildTopicText(news, "en");
        if (topicText == null || topicText.isEmpty()) {
            log.warn("topicText 为空，向量化跳过，newsId={}", news.getId());
            return;
        }

        float[] embedding = embeddingService.embed(topicText);
        Map<String, Object> payload = buildPayload(news);

        VectorPoint point = VectorPoint.builder()
                .id("topic_" + news.getId() + "_en")
                .vector(embedding)
                .payload(payload)
                .build();

        vectorStoreService.upsert(COLLECTION_TOPIC_EN, java.util.List.of(point));
        log.info("Topic 向量生成完成: newsId={}, lang=en", news.getId());
    }

    public void vectorizeTopicZh(News news) {
        String topicText = buildTopicText(news, "zh");
        if (topicText == null || topicText.isEmpty()) {
            return;
        }

        float[] embedding = embeddingService.embed(topicText);
        Map<String, Object> payload = buildPayload(news);

        VectorPoint point = VectorPoint.builder()
                .id("topic_" + news.getId() + "_zh")
                .vector(embedding)
                .payload(payload)
                .build();

        vectorStoreService.upsert(COLLECTION_TOPIC_ZH, java.util.List.of(point));
        log.debug("Topic 向量生成完成: newsId={}, lang=zh", news.getId());
    }

    private Map<String, Object> buildPayload(News news) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("news_id", news.getId());
        payload.put("canonical_id", news.getCanonical_id());
        payload.put("published_at", news.getPublication_time());
        payload.put("category_id", news.getCategory_id());
        payload.put("source", news.getSource());
        return payload;
    }

    private String buildTopicText(News news, String language) {
        StringBuilder sb = new StringBuilder();

        if ("zh".equals(language)) {
            if (news.getTitle_cn() != null && !news.getTitle_cn().isEmpty()) {
                sb.append(news.getTitle_cn());
            }
            if (news.getSummary_cn() != null && !news.getSummary_cn().isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append(" ");
                }
                sb.append(news.getSummary_cn());
            }
        } else {
            if (news.getTitle() != null && !news.getTitle().isEmpty()) {
                sb.append(news.getTitle());
            }
            if (news.getSummary() != null && !news.getSummary().isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append(" ");
                }
                sb.append(news.getSummary());
            }
        }
        return sb.toString().trim();
    }
}