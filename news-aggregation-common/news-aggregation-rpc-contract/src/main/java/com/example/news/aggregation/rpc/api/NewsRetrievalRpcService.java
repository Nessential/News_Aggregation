package com.example.news.aggregation.rpc.api;

import com.example.news.aggregation.rpc.contract.news.RetrievalRequest;
import com.example.news.aggregation.rpc.contract.news.RetrievalResponse;

public interface NewsRetrievalRpcService {
    RetrievalResponse keyword(RetrievalRequest request);

    RetrievalResponse vector(RetrievalRequest request);

    RetrievalResponse hybrid(RetrievalRequest request);
}
