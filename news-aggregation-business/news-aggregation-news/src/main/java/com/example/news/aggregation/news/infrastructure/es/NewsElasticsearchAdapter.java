package com.example.news.aggregation.news.infrastructure.es;

import com.example.news.aggregation.es.config.EsProperties;
import com.example.news.aggregation.es.service.ElasticsearchService;
import com.example.news.aggregation.news.domain.entity.News;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 新闻 ES 适配器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsElasticsearchAdapter {

    private final ElasticsearchService elasticsearchService;
    private final EsProperties esProperties;

    /**
     * 索引新闻到 ES。
     */
    public void indexNews(News news) {
        Map<String, Object> document = buildDocument(news);
        elasticsearchService.indexDocument(esProperties.getIndexName(), String.valueOf(news.getId()), document);
    }

    /**
     * 从 ES 删除新闻。
     */
    public void deleteNews(Long newsId) {
        elasticsearchService.deleteDocument(esProperties.getIndexName(), String.valueOf(newsId));
    }

    private Map<String, Object> buildDocument(News news) {
        Map<String, Object> doc = new HashMap<>();

        doc.put("news_id", news.getId());
        doc.put("title", news.getTitle());
        doc.put("title_cn", news.getTitle_cn());
        doc.put("summary", news.getSummary());
        doc.put("summary_cn", news.getSummary_cn());
        doc.put("context", news.getContext());
        doc.put("context_cn", news.getContext_cn());

        doc.put("source", news.getSource());
        doc.put("category_id", news.getCategory_id());
        doc.put("publication_time", news.getPublication_time());

        doc.put("canonical_id", news.getCanonical_id());
        doc.put("link", news.getLink());
        doc.put("image_url", news.getImage_url());
        doc.put("language", detectLanguage(news));
        doc.put("indexed_at", Instant.now().toEpochMilli());

        return doc;
    }

    private String detectLanguage(News news) {
        if (news.getTitle() != null && !news.getTitle().isEmpty()) {
            return "en";
        }
        if (news.getTitle_cn() != null && !news.getTitle_cn().isEmpty()) {
            return "zh";
        }
        return "en";
    }
}