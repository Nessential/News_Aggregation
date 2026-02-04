package com.example.news.aggregation.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Pipeline执行结果
 * 封装Pipeline执行后的输出数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineResult {
    /**
     * 生成的答案文本
     */
    private String answer;
    
    /**
     * 候选文章ID列表
     */
    private List<Long> candidateIds;
    
    /**
     * 引用信息
     */
    private List<String> citations;
    
    /**
     * 执行耗时 (毫秒)
     */
    private Long executionTimeMs;
    
    /**
     * LLM调用次数
     */
    private Integer llmCallCount;
    
    /**
     * 是否成功
     */
    private Boolean success;
    
    /**
     * 错误信息 (如果失败)
     */
    private String errorMessage;
    
    /**
     * 额外数据 (扩展字段)
     */
    private Map<String, Object> extraData;
}
