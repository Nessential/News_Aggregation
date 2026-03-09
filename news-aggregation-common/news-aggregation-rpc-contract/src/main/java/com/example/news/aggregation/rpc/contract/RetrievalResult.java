package com.example.news.aggregation.rpc.contract;

import java.io.Serializable;

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
public class RetrievalResult implements Serializable {
    private static final long serialVersionUID = 1L;
    private String id;
    private String title;
    private String content;
    private String url;
    private Double score;
    private String source;
    private String publishedAt;
    
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
