package com.example.news.aggregation.news.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量查询请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdsRequest {
    // 文章ID列表
    private List<Long> ids;
}
