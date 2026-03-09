package com.example.news.aggregation.llm.springai.rpc.provider;

import com.example.news.aggregation.llm.springai.contract.GeneratorDraft;
import com.example.news.aggregation.llm.springai.contract.GeneratorRequest;
import com.example.news.aggregation.llm.springai.service.GeneratorService;
import com.example.news.aggregation.rpc.api.LlmGeneratorRpcService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;

@Slf4j
@DubboService
@RequiredArgsConstructor
public class LlmGeneratorRpcServiceImpl implements LlmGeneratorRpcService {

    private final GeneratorService generatorService;
    private final ObjectMapper objectMapper;

    @Override
    public com.example.news.aggregation.rpc.contract.GeneratorDraft generate(
            com.example.news.aggregation.rpc.contract.GeneratorRequest request) {
        try {
            GeneratorRequest generatorRequest = objectMapper.convertValue(request, GeneratorRequest.class);
            GeneratorDraft result = generatorService.generate(
                    generatorRequest.getQuery(),
                    generatorRequest.getQueryInterpretation(),
                    generatorRequest.getTaskFamily(),
                    generatorRequest.getEvidence(),
                    generatorRequest.getRetrievalMode()
            );
            return objectMapper.convertValue(result, com.example.news.aggregation.rpc.contract.GeneratorDraft.class);
        } catch (Exception e) {
            log.error("RPC generate failed", e);
            return com.example.news.aggregation.rpc.contract.GeneratorDraft.conservative("rpc_error");
        }
    }
}
