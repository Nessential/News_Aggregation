package com.example.news.aggregation.rpc.api;

import com.example.news.aggregation.rpc.contract.RouterRequest;
import com.example.news.aggregation.rpc.contract.RouterResult;

public interface LlmRouterRpcService {
    RouterResult route(RouterRequest request);
}
