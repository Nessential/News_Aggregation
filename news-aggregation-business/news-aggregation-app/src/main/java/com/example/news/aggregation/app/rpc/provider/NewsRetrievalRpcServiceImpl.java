package com.example.news.aggregation.app.rpc.provider;

import com.example.news.aggregation.news.dto.RetrievalRequest;
import com.example.news.aggregation.news.dto.RetrievalResponse;
import com.example.news.aggregation.news.service.RetrievalService;
import com.example.news.aggregation.rpc.api.NewsRetrievalRpcService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;

@Slf4j
@DubboService
@RequiredArgsConstructor
public class NewsRetrievalRpcServiceImpl implements NewsRetrievalRpcService {

    private final RetrievalService retrievalService;
    private final ObjectMapper objectMapper;

    @Override
    public com.example.news.aggregation.rpc.contract.news.RetrievalResponse keyword(
            com.example.news.aggregation.rpc.contract.news.RetrievalRequest request) {
        return invoke(request, Mode.KEYWORD);
    }

    @Override
    public com.example.news.aggregation.rpc.contract.news.RetrievalResponse vector(
            com.example.news.aggregation.rpc.contract.news.RetrievalRequest request) {
        return invoke(request, Mode.VECTOR);
    }

    @Override
    public com.example.news.aggregation.rpc.contract.news.RetrievalResponse hybrid(
            com.example.news.aggregation.rpc.contract.news.RetrievalRequest request) {
        return invoke(request, Mode.HYBRID);
    }

    private com.example.news.aggregation.rpc.contract.news.RetrievalResponse invoke(
            com.example.news.aggregation.rpc.contract.news.RetrievalRequest request, Mode mode) {
        try {
            RetrievalRequest rpcRequest = objectMapper.convertValue(request, RetrievalRequest.class);
            RetrievalResponse response = switch (mode) {
                case KEYWORD -> RetrievalResponse.builder().results(
                        retrievalService.keywordSearch(rpcRequest.getQuery(), rpcRequest.getTopK(), rpcRequest.getFilters())
                ).build();
                case VECTOR -> RetrievalResponse.builder().results(
                        retrievalService.vectorSearch(rpcRequest.getQuery(), rpcRequest.getTopK(), rpcRequest.getMinScore(), rpcRequest.getFilters())
                ).build();
                case HYBRID -> RetrievalResponse.builder().results(
                        retrievalService.hybridSearch(rpcRequest.getQuery(), rpcRequest.getTopK(), rpcRequest.getMinScore(), rpcRequest.getFilters())
                ).build();
            };
            return objectMapper.convertValue(response, com.example.news.aggregation.rpc.contract.news.RetrievalResponse.class);
        } catch (Exception e) {
            log.error("RPC retrieval failed, mode={}", mode, e);
            return com.example.news.aggregation.rpc.contract.news.RetrievalResponse.builder()
                    .results(java.util.List.of())
                    .build();
        }
    }

    private enum Mode {
        KEYWORD,
        VECTOR,
        HYBRID
    }
}
