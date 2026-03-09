package com.example.news.aggregation.rpc.contract.news;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    // 
    private String query;
    // 
    private Integer topK;
    // 
    private Double minScore;
    // 
    private java.util.Map<String, Object> filters;
}
