package com.example.news.aggregation.agent.client;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 新闻服务客户端（HTTP）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsClient {

    private final RestTemplate restTemplate;

    @Value("${app.news.base-url:http://localhost:8082}")
    private String newsBaseUrl;

    /**
     * 批量查询文章详情
     */
    public List<NewsArticleDto> getArticlesByIds(List<Long> ids) {
        String url = newsBaseUrl + "/api/news/articles/by-ids";
        IdsRequest request = IdsRequest.builder()
                .ids(ids)
                .build();
        try {
            ResponseEntity<ArticlesResponse> response = restTemplate.postForEntity(
                    url, request, ArticlesResponse.class);
            ArticlesResponse body = response.getBody();
            return body != null && body.getArticles() != null ? body.getArticles() : new ArrayList<>();
        } catch (Exception e) {
            log.warn("NewsClient request failed, error={}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class IdsRequest {
        // 文章ID列表
        private List<Long> ids;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NewsArticleDto {
        // 文章ID
        private Long id;
        // 标题
        private String title;
        // 原文链接
        private String url;
        // 正文内容
        private String content;
        // 来源
        private String source;
        // 发布时间（字符串）
        private String publishedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ArticlesResponse {
        // 文章列表
        private List<NewsArticleDto> articles;
    }
}
