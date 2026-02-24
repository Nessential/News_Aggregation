package com.example.news.aggregation.agent.domain;

import com.example.news.aggregation.llm.springai.contract.RouterResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 执行上下文。
 * 封装执行过程中所需的输入参数与状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineContext {
    /** 会话状态。 */
    private SessionState sessionState;

    /** Router 分析结果。 */
    private RouterResult routerResult;

    /** 用户原始 query。 */
    private String query;

    /** 证据文档 (文章ID -> 文章内容)。 */
    private Map<Long, String> evidence;

    /** 检索到的候选文档 ID。 */
    private List<Long> candidateIds;

    /** 额外参数 (扩展字段)。 */
    private Map<String, Object> extraParams;

    /** 获取用户约束。 */
    public Constraints getConstraints() {
        return sessionState != null ? sessionState.getConstraints() : null;
    }

    /** 获取对话历史。 */
    public List<String> getHistory() {
        return sessionState != null ? sessionState.getHistory() : null;
    }
}