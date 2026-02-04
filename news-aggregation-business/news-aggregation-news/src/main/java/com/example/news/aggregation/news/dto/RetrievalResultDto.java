package com.example.news.aggregation.news.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalResultDto {
    // 文章主键（news_id 或 ES 文档 id）
    private Long articleId;
    // 相关性得分
    private Double score;
    // 证据片段（摘要）
    private String snippet;
    // 原始元数据（序列化后的 source/payload）
    private String metadata;
}
