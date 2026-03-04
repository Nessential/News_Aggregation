package com.example.news.aggregation.llm.springai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 计划请求
 * 由 Router 结果与上下文驱动 PlannerGraph 生成 Plan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanRequest {

    /** 用户查询 */
    private String query;

    /** Router 输出结果 */
    private RouterResult routerResult;

    /** 额外上下文（可选） */
    private Map<String, Object> context;

    /** 计划结构版本（可选） */
    private String planSchema;

    /** 语义版本（可选） */
    private String semanticVersion;
}
