package com.example.news.aggregation.news.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RrfRequest {
    // 待融合的多路结果列表
    private List<List<RetrievalResultDto>> lists;
    // 融合后结果数量上限（可选）
    private Integer topK;
}
