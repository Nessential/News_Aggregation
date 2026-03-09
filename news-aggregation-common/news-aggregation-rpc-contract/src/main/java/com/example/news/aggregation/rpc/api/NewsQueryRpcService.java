package com.example.news.aggregation.rpc.api;

import com.example.news.aggregation.rpc.contract.news.ArticlesResponse;
import com.example.news.aggregation.rpc.contract.news.IdsRequest;

public interface NewsQueryRpcService {
    ArticlesResponse getByIds(IdsRequest request);
}
