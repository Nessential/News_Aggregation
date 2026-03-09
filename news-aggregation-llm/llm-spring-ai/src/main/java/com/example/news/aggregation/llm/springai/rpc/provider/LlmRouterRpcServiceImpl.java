package com.example.news.aggregation.llm.springai.rpc.provider;

import com.example.news.aggregation.llm.springai.contract.RouterRequest;
import com.example.news.aggregation.llm.springai.contract.RouterResult;
import com.example.news.aggregation.llm.springai.service.RouterService;
import com.example.news.aggregation.rpc.api.LlmRouterRpcService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;

@Slf4j
@DubboService
@RequiredArgsConstructor
public class LlmRouterRpcServiceImpl implements LlmRouterRpcService {

    private final RouterService routerService;
    private final ObjectMapper objectMapper;

    @Override
    public com.example.news.aggregation.rpc.contract.RouterResult route(
            com.example.news.aggregation.rpc.contract.RouterRequest request) {
        try {
            RouterRequest routerRequest = objectMapper.convertValue(request, RouterRequest.class);
            RouterResult result = routerService.route(routerRequest);
            return objectMapper.convertValue(result, com.example.news.aggregation.rpc.contract.RouterResult.class);
        } catch (Exception e) {
            log.error("RPC route failed", e);
            return com.example.news.aggregation.rpc.contract.RouterResult.defaultQA();
        }
    }
}
