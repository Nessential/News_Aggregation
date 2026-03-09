package com.example.news.aggregation.rpc.api;

import com.example.news.aggregation.rpc.contract.GeneratorDraft;
import com.example.news.aggregation.rpc.contract.GeneratorRequest;

public interface LlmGeneratorRpcService {
    GeneratorDraft generate(GeneratorRequest request);
}
