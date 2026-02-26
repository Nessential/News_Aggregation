package com.example.news.aggregation.agent.adapter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * NewsService Mock 实现。
 * 临时实现，用于 Agent 模块开发和测试。
 *
 * 当真实的 NewsService Bean 存在时，此 Bean 不会被创建。
 */
@Slf4j
@Service
@ConditionalOnMissingBean(name = "newsService")
public class MockNewsService implements NewsService {

    @Override
    public List<NewsArticle> getArticlesByIds(List<Long> articleIds) {
        log.warn("Using MockNewsService - returning mock data for article IDs: {}", articleIds);

        // 返回模拟数据

        return articleIds.stream()
                .map(id -> NewsArticle.builder()
                        .id(id)
                        .title("Mock Article " + id)
                        .content("This is mock content for article " + id + ". "
                                + "In production, this would be the actual article content retrieved from the database.")
                        .url("https://example.com/article/" + id)
                        .source("Mock Source")
                        .publishedAt(LocalDateTime.now().minusDays(id % 7))
                        .build())
                .collect(Collectors.toList());
    }
}