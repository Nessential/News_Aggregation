package com.example.news.aggregation.embedding.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddingRequest {
    @JSONField(name = "model")
    private String model;

    @JSONField(name = "input")
    private String input;
}