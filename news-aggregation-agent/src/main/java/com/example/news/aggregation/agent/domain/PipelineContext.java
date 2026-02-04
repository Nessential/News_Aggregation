package com.example.news.aggregation.agent.domain;


import com.example.news.aggregation.llm.springai.contract.RouterResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Pipeline执行上下文
 * 封装Pipeline执行所需的所有输入参数
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineContext {
    /**
     * 会话状态
     */
    private SessionState sessionState;
    
    /**
     * Router分析结果
     */
    private RouterResult routerResult;
    
    /**
     * 用户原始query
     */
    private String query;
    
    /**
     * 证据文档 (文章ID -> 文章内容)
     */
    private Map<Long, String> evidence;
    
    /**
     * 检索到的候选文章ID
     */
    private List<Long> candidateIds;
    
    /**
     * 额外参数 (扩展字段)
     */
    private Map<String, Object> extraParams;
    
    /**
     * 获取用户约束
     */
    public Constraints getConstraints() {
        return sessionState != null ? sessionState.getConstraints() : null;
    }
    
    /**
     * 获取对话历史
     */
    public List<String> getHistory() {
        return sessionState != null ? sessionState.getHistory() : null;
    }
}
