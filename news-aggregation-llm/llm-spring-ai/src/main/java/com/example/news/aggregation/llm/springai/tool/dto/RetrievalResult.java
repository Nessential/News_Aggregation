package com.example.news.aggregation.llm.springai.tool.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalResult {
    private String id;
    private String title;
    private String content;
    private String url;
    private Double score;
    private String source;
    
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
