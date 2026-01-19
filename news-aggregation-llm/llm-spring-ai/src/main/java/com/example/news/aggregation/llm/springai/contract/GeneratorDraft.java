package com.example.news.aggregation.llm.springai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 生成器草稿契约
 * 包含LLM生成的答案、引用和质量评分
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratorDraft {
    
    /** 生成的答案文本 */
    private String answer;
    
    /** 引用列表 */
    private List<Citation> citations;
    
    /** 质量评分 (0.0-1.0) */
    private Double qualityScore;
    
    /**
     * 引用信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Citation {
        /** 来源文档 ID */
        private String sourceId;
        
        /** 引用上下文文本 */
        private String text;
        
        /** 引用位置 */
        private Integer position;
    }
    
    /**
     * 创建保守型充底答案
     * 当验证失败时使用
     * 
     * @param evidenceSummary 证据摘要
     * @return 保守型答案
     */
    public static GeneratorDraft conservative(String evidenceSummary) {
        return GeneratorDraft.builder()
                .answer("根据可用信息：" + evidenceSummary)
                .citations(new ArrayList<>())
                .qualityScore(0.5)
                .build();
    }
}
