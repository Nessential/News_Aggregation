package com.example.news.aggregation.llm.springai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Router 请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteRequest {

    /** 会话 ID */
    private String sessionId;

    /** 用户查询 */
    private String query;

    /** 对话历史（可选） */
    private List<String> history;

    /** 约束条件（时间/分类/语言等，可选） */
    private Map<String, Object> constraints;
}
