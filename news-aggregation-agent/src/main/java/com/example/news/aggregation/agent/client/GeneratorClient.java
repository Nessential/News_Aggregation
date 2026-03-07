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
import java.util.stream.Collectors;

/**
 * Generator 客户端(HTTP)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeneratorClient {

    private final RestTemplate restTemplate;

    @Value("${app.llm.base-url:http://localhost:8081}")
    private String llmBaseUrl;

    /**
     * 调用 GeneratorGraph。
     * @param query 原始用户查询
     * @param queryInterpretation 意图识别阶段的查询理解/改写（优先用于生成，为空时用 query）
     */
    public GeneratorDraft generate(String query, String queryInterpretation, String taskFamily, List<RetrievalResult> evidence, String retrievalMode) {
        // TODO 改为 RPC 调用
        String url = llmBaseUrl + "/api/graph/generate";
        GeneratorRequest request = GeneratorRequest.builder()
                .query(query)
                .queryInterpretation(queryInterpretation)
                .taskFamily(taskFamily)
                .retrievalMode(retrievalMode)
                .evidence(evidence)
                .build();
        try {
            int evidenceCount = evidence != null ? evidence.size() : 0;
            long nonEmptyContentCount = evidence != null ? evidence.stream()
                    .filter(e -> e.getContent() != null && !e.getContent().isBlank())
                    .count() : 0;
            String sample = evidence != null ? evidence.stream()
                    .limit(2)
                    .map(e -> "id=" + e.getId() + "|contentLen=" + (e.getContent() != null ? e.getContent().length() : 0))
                    .collect(Collectors.joining(",")) : "";
            log.info("[client] 调用生成服务FLOW|agent|client=generator|step=start|url={}|taskFamily={}|evidenceCount={}|nonEmptyContentCount={}|sample={}|next=LLM-Generator",
                    url, taskFamily, evidenceCount, nonEmptyContentCount, sample);
            ResponseEntity<GeneratorDraft> response = restTemplate.postForEntity(url, request, GeneratorDraft.class);
            GeneratorDraft body = response.getBody();
            int answerLength = body != null && body.getAnswer() != null ? body.getAnswer().length() : 0;
            log.info("[client] 生成返回FLOW|agent|client=generator|step=end|answerLength={}|next=响应组装", answerLength);
            return body;
        } catch (Exception e) {
            log.warn("GeneratorClient generate failed, error={}", e.getMessage());
            return null;
        }
    }

    public GeneratorDraft generate(String query, String taskFamily, List<RetrievalResult> evidence) {
        return generate(query, null, taskFamily, evidence, null);
    }
}