package com.example.news.aggregation.agent.client;

import com.example.news.aggregation.llm.springai.contract.GeneratorDraft;
import com.example.news.aggregation.llm.springai.contract.GeneratorRequest;
import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Generator服务客户端（HTTP）
 * 调用LLM侧Graph生成草稿
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeneratorClient {

    private final RestTemplate restTemplate;

    @Value("${app.llm.base-url:http://localhost:8081}")
    private String llmBaseUrl;

    /**
     * 调用GeneratorGraph生成草稿
     */
    public GeneratorDraft generate(String query, String taskFamily, List<RetrievalResult> evidence) {
        String url = llmBaseUrl + "/api/graph/generate";
        GeneratorRequest request = GeneratorRequest.builder()
                .query(query)
                .taskFamily(taskFamily)
                .evidence(evidence)
                .build();
        try {
            ResponseEntity<GeneratorDraft> response = restTemplate.postForEntity(url, request, GeneratorDraft.class);
            return response.getBody();
        } catch (Exception e) {
            log.warn("GeneratorClient generate failed, error={}", e.getMessage());
            return null;
        }
    }
}
