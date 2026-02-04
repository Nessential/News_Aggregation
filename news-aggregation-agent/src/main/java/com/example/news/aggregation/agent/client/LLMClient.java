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

/**
 * LLM 生成客户端（HTTP）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LLMClient {

    private final RestTemplate restTemplate;

    @Value("${app.llm.base-url:http://localhost:8081}")
    private String llmBaseUrl;

    /**
     * 调用 LLM 生成接口
     */
    public String generate(String prompt) {
        String url = llmBaseUrl + "/api/llm/generate";
        GenerateRequest request = GenerateRequest.builder()
                .prompt(prompt)
                .build();
        try {
            ResponseEntity<GenerateResponse> response = restTemplate.postForEntity(
                    url, request, GenerateResponse.class);
            GenerateResponse body = response.getBody();
            return body != null ? body.getContent() : "";
        } catch (Exception e) {
            log.warn("LLMClient generate failed, error={}", e.getMessage());
            return "";
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class GenerateRequest {
        // 提示词
        private String prompt;
        // 模型名称（可选）
        private String model;
        // 温度（可选）
        private Double temperature;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class GenerateResponse {
        // 生成内容
        private String content;
        // 令牌使用量（可选）
        private Integer usageTokens;
    }
}
