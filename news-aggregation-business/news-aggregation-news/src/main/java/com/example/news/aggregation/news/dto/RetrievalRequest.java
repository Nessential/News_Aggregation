package com.example.news.aggregation.news.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalRequest {
    // 用户查询文本
    private String query;
    // 结果数量上限
    private Integer topK;
    // 向量检索最小得分阈值（可选）
    private Double minScore;
    // 过滤条件（可选）
    private java.util.Map<String, Object> filters;
}
