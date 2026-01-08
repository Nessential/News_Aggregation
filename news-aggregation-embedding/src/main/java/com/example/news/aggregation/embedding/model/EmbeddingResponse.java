package com.example.news.aggregation.embedding.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;
import java.util.List;

@Data
public class EmbeddingResponse {
    @JSONField(name = "object")
    private String object;

    @JSONField(name = "data")
    private List<EmbeddingData> data;

    @JSONField(name = "model")
    private String model;

    @JSONField(name = "usage")
    private Usage usage;

    @Data
    public static class EmbeddingData {
        @JSONField(name = "object")
        private String object;

        @JSONField(name = "index")
        private int index;

        @JSONField(name = "embedding")
        private List<Float> embedding;
    }

    @Data
    public static class Usage {
        @JSONField(name = "prompt_tokens")
        private int promptTokens;

        @JSONField(name = "total_tokens")
        private int totalTokens;
    }
}