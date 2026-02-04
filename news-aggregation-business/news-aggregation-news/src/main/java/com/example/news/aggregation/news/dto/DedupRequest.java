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
public class DedupRequest {
    // 待去重的结果列表
    private List<RetrievalResultDto> results;
    // 去重后结果数量上限（可选）
    private Integer topK;
}
