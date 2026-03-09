package com.example.news.aggregation.agent.client;

import com.example.news.aggregation.llm.springai.contract.GeneratorDraft;
import com.example.news.aggregation.llm.springai.contract.GeneratorRequest;
import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import com.example.news.aggregation.rpc.api.LlmGeneratorRpcService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeneratorClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @DubboReference(check = false, timeout = 10000, retries = 0)
    private LlmGeneratorRpcService llmGeneratorRpcService;

    @Value("${app.llm.base-url:http://localhost:8081}")
    private String llmBaseUrl;

    @Value("${app.rpc.enabled:false}")
    private boolean rpcEnabled;

    public GeneratorDraft generate(String query, String queryInterpretation, String taskFamily, List<RetrievalResult> evidence, String retrievalMode) {
        GeneratorRequest request = GeneratorRequest.builder()
                .query(query)
                .queryInterpretation(queryInterpretation)
                .taskFamily(taskFamily)
                .retrievalMode(retrievalMode)
                .evidence(evidence)
                .build();

        if (rpcEnabled) {
            try {
                com.example.news.aggregation.rpc.contract.GeneratorRequest rpcRequest =
                        objectMapper.convertValue(request, com.example.news.aggregation.rpc.contract.GeneratorRequest.class);
                com.example.news.aggregation.rpc.contract.GeneratorDraft rpcDraft =
                        llmGeneratorRpcService.generate(rpcRequest);
                return objectMapper.convertValue(rpcDraft, GeneratorDraft.class);
            } catch (Exception e) {
                log.warn("GeneratorClient rpc failed, fallback to http. error={}", e.getMessage());
            }
        }

        String url = llmBaseUrl + "/api/graph/generate";
        try {
            ResponseEntity<GeneratorDraft> response = restTemplate.postForEntity(url, request, GeneratorDraft.class);
            return response.getBody();
        } catch (Exception e) {
            log.warn("GeneratorClient generate failed, error={}", e.getMessage());
            return null;
        }
    }

    public GeneratorDraft generate(String query, String taskFamily, List<RetrievalResult> evidence) {
        return generate(query, null, taskFamily, evidence, null);
    }
}


