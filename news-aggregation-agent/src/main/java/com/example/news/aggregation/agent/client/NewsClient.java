package com.example.news.aggregation.agent.client;

import com.example.news.aggregation.rpc.api.NewsQueryRpcService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NewsClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @DubboReference(check = false, timeout = 8000, retries = 0)
    private NewsQueryRpcService newsQueryRpcService;

    @Value("${app.news.base-url:http://localhost:8082}")
    private String newsBaseUrl;

    @Value("${app.rpc.enabled:false}")
    private boolean rpcEnabled;

    public List<NewsArticleDto> getArticlesByIds(List<Long> ids) {
        IdsRequest request = IdsRequest.builder().ids(ids).build();
        try {
            ArticlesResponse body;
            if (rpcEnabled) {
                com.example.news.aggregation.rpc.contract.news.IdsRequest rpcRequest =
                        objectMapper.convertValue(request, com.example.news.aggregation.rpc.contract.news.IdsRequest.class);
                com.example.news.aggregation.rpc.contract.news.ArticlesResponse rpcResponse =
                        newsQueryRpcService.getByIds(rpcRequest);
                body = objectMapper.convertValue(rpcResponse, ArticlesResponse.class);
            } else {
                String url = newsBaseUrl + "/api/news/articles/by-ids";
                ResponseEntity<ArticlesResponse> response = restTemplate.postForEntity(url, request, ArticlesResponse.class);
                body = response.getBody();
            }
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
        private List<Long> ids;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NewsArticleDto {
        private Long id;
        private String title;
        private String url;
        private String content;
        private String source;
        private String publishedAt;
        private String imageUrl;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ArticlesResponse {
        private List<NewsArticleDto> articles;
    }
}
