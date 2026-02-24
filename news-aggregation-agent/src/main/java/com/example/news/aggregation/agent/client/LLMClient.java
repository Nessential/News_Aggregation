package com.example.news.aggregation.agent.client;

import com.example.news.aggregation.llm.springai.contract.GenerateRequest;
import com.example.news.aggregation.llm.springai.contract.GenerateResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * LLM生成客户端（HTTP）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LLMClient {

    private final RestTemplate restTemplate;

    @Value("${app.llm.base-url:http://localhost:8081}")
    private String llmBaseUrl;

    /**
     * 调用LLM生成接口
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
}