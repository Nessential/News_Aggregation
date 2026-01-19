package com.example.news.aggregation.llm.springai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Router结果契约
 * 包含意图识别的结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouterResult {
    
    /** 任务类型: QA, SUMMARY, COMPARE, TIMELINE, DEEP_DIVE */
    private String taskFamily;
    
    /** 检索模式: SEMANTIC, KEYWORD, HYBRID, GRAPH */
    private String retrievalMode;
    
    /** 风险级别: LOW, MEDIUM, HIGH */
    private String riskLevel;
    
    /** 是否需要澄清 */
    private Boolean needsClarification;
    
    /** 澄清问题 */
    private String clarificationQuestion;
    
    /**
     * 创建默认QA结果（充底用）
     * 当Router失败时返回此结果
     */
    public static RouterResult defaultQA() {
        return RouterResult.builder()
                .taskFamily("QA")
                .retrievalMode("HYBRID")
                .riskLevel("LOW")
                .needsClarification(false)
                .build();
    }
}
