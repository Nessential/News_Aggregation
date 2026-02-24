package com.example.news.aggregation.llm.springai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Router请求参数
 * 用于路由识别与参数抽取
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouterRequest {

    /** 会话ID */
    private String sessionId;

    /** 用户查询文本 */
    private String query;

    /** 对话历史（可选） */
    private List<String> history;

    /** 约束条件（时间/分类/语言等，可选） */
    private Map<String, Object> constraints;
}