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
 * 新闻 ES 适配器
 * 负责将 News 实体转换为 ES 文档格式
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsElasticsearchAdapter {

    private final ElasticsearchService elasticsearchService;
    private final EsProperties esProperties;

    /**
     * 索引新闻到 ES
     */
    public void indexNews(News news) {
        Map<String, Object> document = buildDocument(news);
        elasticsearchService.indexDocument(
                esProperties.getIndexName(),
                String.valueOf(news.getId()),
                document
        );
    }

    /**
     * 从 ES 删除新闻
     */
    public void deleteNews(Long newsId) {
        elasticsearchService.deleteDocument(
                esProperties.getIndexName(),
                String.valueOf(newsId)
        );
    }

    /**
     * 构建 ES 文档
     * 只索引用于搜索和聚合的字段，减少冗余存储
     */
    private Map<String, Object> buildDocument(News news) {
        Map<String, Object> doc = new HashMap<>();

        // 🔍 搜索字段（核心）
        doc.put("news_id", news.getId());
        doc.put("title", news.getTitle());
        doc.put("title_cn", news.getTitle_cn());
        doc.put("summary", news.getSummary());
        doc.put("summary_cn", news.getSummary_cn());
        doc.put("context", news.getContext());        // 正文（全文搜索）
        doc.put("context_cn", news.getContext_cn());  // 中文正文（全文搜索）

        // 📊 聚合字段（统计分析）
        doc.put("source", news.getSource());
        doc.put("category", news.getCategory());
        doc.put("publication_time", news.getPublication_time());

        // 🔗 关联字段（辅助功能）
        doc.put("canonical_id", news.getCanonical_id());  // Story 归簇
        doc.put("link", news.getLink());                  // 原文链接
        doc.put("image_url", news.getImage_url());        // 图片（列表展示）

        // 🌐 语言检测
        String language = detectLanguage(news);
        doc.put("language", language);

        // 🕐 元数据
        doc.put("indexed_at", Instant.now().toEpochMilli());

        // 注：不索引状态字段（deleted, content_status, vector_status 等）
        // 这些字段由 MySQL 管理，ES 只索引成功的记录

        return doc;
    }

    /**
     * 检测原文语言
     * 
     * 注意：
     * - 大部分新闻都有中英文版本（翻译后），language 标识的是原文语言
     * - 用于新闻订阅推送和来源统计
     * - 规则：有英文 title（原文）→ en；只有中文 title → zh
     */
    private String detectLanguage(News news) {
        // 如果有英文原文标题，标记为英文
        if (news.getTitle() != null && !news.getTitle().isEmpty()) {
            return "en";
        }
        // 如果只有中文原文标题，标记为中文
        if (news.getTitle_cn() != null && !news.getTitle_cn().isEmpty()) {
            return "zh";
        }
        // 默认英文（大部分外网新闻）
        return "en";
    }
}