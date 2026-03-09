package com.example.news.aggregation.app.rpc.provider;

import com.example.news.aggregation.news.domain.entity.News;
import com.example.news.aggregation.news.dto.ArticlesResponse;
import com.example.news.aggregation.news.dto.IdsRequest;
import com.example.news.aggregation.news.dto.NewsArticleDto;
import com.example.news.aggregation.news.infrastructure.mapper.NewsMapper;
import com.example.news.aggregation.rpc.api.NewsQueryRpcService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@DubboService
@RequiredArgsConstructor
public class NewsQueryRpcServiceImpl implements NewsQueryRpcService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final NewsMapper newsMapper;
    private final ObjectMapper objectMapper;

    @Override
    public com.example.news.aggregation.rpc.contract.news.ArticlesResponse getByIds(
            com.example.news.aggregation.rpc.contract.news.IdsRequest request) {
        try {
            IdsRequest idsRequest = objectMapper.convertValue(request, IdsRequest.class);
            if (idsRequest == null || idsRequest.getIds() == null || idsRequest.getIds().isEmpty()) {
                return com.example.news.aggregation.rpc.contract.news.ArticlesResponse.builder()
                        .articles(Collections.emptyList())
                        .build();
            }
            List<News> newsList = newsMapper.selectBatchIds(idsRequest.getIds());
            List<NewsArticleDto> articles = newsList.stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());
            ArticlesResponse response = ArticlesResponse.builder().articles(articles).build();
            return objectMapper.convertValue(response, com.example.news.aggregation.rpc.contract.news.ArticlesResponse.class);
        } catch (Exception e) {
            log.error("RPC getByIds failed", e);
            return com.example.news.aggregation.rpc.contract.news.ArticlesResponse.builder()
                    .articles(Collections.emptyList())
                    .build();
        }
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
