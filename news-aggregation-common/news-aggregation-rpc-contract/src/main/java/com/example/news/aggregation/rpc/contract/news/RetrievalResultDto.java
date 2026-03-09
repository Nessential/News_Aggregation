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
public class RetrievalResultDto implements Serializable {
    private static final long serialVersionUID = 1L;
    // ews_id ?ES  id?
    private Long articleId;
    // ?
    private Double score;
    // 
    private String snippet;
    // 
    private String fullContent;
    // 
    private String publishedAt;
    // ?source/payload?
    private String metadata;
}
