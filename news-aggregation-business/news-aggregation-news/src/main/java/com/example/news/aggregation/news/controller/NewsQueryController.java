package com.example.news.aggregation.news.controller;

import com.example.news.aggregation.news.domain.entity.News;
import com.example.news.aggregation.news.dto.ArticlesResponse;
import com.example.news.aggregation.news.dto.IdsRequest;
import com.example.news.aggregation.news.dto.NewsArticleDto;
import com.example.news.aggregation.news.infrastructure.mapper.NewsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 新闻查询控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/news/articles")
@RequiredArgsConstructor
public class NewsQueryController {

    private final NewsMapper newsMapper;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    /**
     * 批量查询文章详情
     */
    @PostMapping("/by-ids")
    public ResponseEntity<ArticlesResponse> getByIds(@RequestBody IdsRequest request) {
        if (request == null || request.getIds() == null || request.getIds().isEmpty()) {
            return ResponseEntity.ok(ArticlesResponse.builder().articles(Collections.emptyList()).build());
        }

        List<News> newsList = newsMapper.selectBatchIds(request.getIds());
        List<NewsArticleDto> articles = newsList.stream()
                .map(this::toDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(ArticlesResponse.builder().articles(articles).build());
    }

    private NewsArticleDto toDto(News news) {
        if (news == null) {
            return null;
        }
        String publishedAt = news.getPublication_time() != null
                ? DATE_FORMATTER.format(Instant.ofEpochMilli(news.getPublication_time()))
                : null;
        return NewsArticleDto.builder()
                .id(news.getId())
                .title(news.getTitle())
                .url(news.getLink())
                .content(news.getContext())
                .source(news.getSource())
                .publishedAt(publishedAt)
                .build();
    }
}
