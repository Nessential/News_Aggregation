package com.example.news.aggregation.embedding.service.impl;

import com.alibaba.fastjson2.JSON;
import com.example.news.aggregation.embedding.config.EmbeddingProperties;
import com.example.news.aggregation.embedding.exception.EmbeddingErrorCode;
import com.example.news.aggregation.embedding.exception.EmbeddingException;
import com.example.news.aggregation.embedding.model.EmbeddingRequest;
import com.example.news.aggregation.embedding.model.EmbeddingResponse;
import com.example.news.aggregation.embedding.service.EmbeddingService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ZhipuEmbeddingServiceImpl implements EmbeddingService {

    private final EmbeddingProperties properties;
    private OkHttpClient httpClient;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    /**
     * 初始化 HTTP 客户端和配置
     * 在 Spring 容器启动后自动调用
     */
    /**
     * 初始化 HTTP 客户端和配置
     * 在 Spring 容器启动后自动调用
     */
    @PostConstruct
    public void init() {
        log.info("初始化智谱 Embedding 服务:");
        log.info("  baseUrl: {}", properties.getBaseUrl());
        log.info("  model: {}", properties.getModel());
        log.info("  dimensions: {}", properties.getDimensions());
        log.info("  apiKey: {}", properties.getApiKey() != null ? 
            properties.getApiKey().substring(0, Math.min(10, properties.getApiKey().length())) + "..." : "null");

        // 创建 HTTP 客户端，配置超时时间
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(properties.getConnectTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.getReadTimeout(), TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new EmbeddingException("输入文本不能为空", EmbeddingErrorCode.EMPTY_INPUT);
        }

        EmbeddingRequest request = EmbeddingRequest.builder()
                .model(properties.getModel())
                .input(text)
                .build();

        String requestBody = JSON.toJSONString(request);
        String url = properties.getBaseUrl() + "/embeddings";
        Request httpRequest = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + properties.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, JSON_MEDIA_TYPE))
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                handleErrorResponse(response);
            }

            String responseBody = response.body() != null ? response.body().string() : "";
            EmbeddingResponse embeddingResponse = JSON.parseObject(responseBody, EmbeddingResponse.class);

            if (embeddingResponse == null || embeddingResponse.getData() == null || embeddingResponse.getData().isEmpty()) {
                throw new EmbeddingException("Embedding API 响应为空", EmbeddingErrorCode.INVALID_RESPONSE);
            }

            List<Float> embedding = embeddingResponse.getData().get(0).getEmbedding();
            return toFloatArray(embedding);

        } catch (IOException e) {
            log.error("Embedding API 调用失败", e);
            throw new EmbeddingException("Embedding API 调用失败: " + e.getMessage(), e, EmbeddingErrorCode.API_CALL_FAILED);
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new EmbeddingException("输入文本列表不能为空", EmbeddingErrorCode.EMPTY_INPUT);
        }

        List<float[]> results = new ArrayList<>();
        for (String text : texts) {
            results.add(embed(text));
        }
        return results;
    }

    @Override
    public int getDimension() {
        return properties.getDimensions();
    }

    private void handleErrorResponse(Response response) throws IOException {
        int code = response.code();
        String body = response.body() != null ? response.body().string() : "";

        if (code == 401) {
            throw new EmbeddingException("API 认证失败: " + body, EmbeddingErrorCode.AUTHENTICATION_FAILED);
        } else if (code == 429) {
            throw new EmbeddingException("API 调用频率超限: " + body, EmbeddingErrorCode.RATE_LIMIT_EXCEEDED);
        } else {
            throw new EmbeddingException("API 调用失败, HTTP " + code + ": " + body, EmbeddingErrorCode.API_CALL_FAILED);
        }
    }

    private float[] toFloatArray(List<Float> list) {
        float[] array = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }
}