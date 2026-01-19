package com.example.news.aggregation.llm.springai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 聊天响应契约
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    
    /** 会话 ID */
    private String sessionId;
    
    /** LLM 生成的答案 */
    private String answer;
    
    /** 任务类型 (QA/SUMMARY/COMPARE等) */
    private String taskFamily;
    
    /** 引用来源列表 */
    private List<Source> sources;
    
    /** 时间戳 */
    private Long timestamp;
    
    /**
     * 引用来源
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Source {
        /** 文档 ID */
        private String id;
        
        /** 标题 */
        private String title;
        
        /** 原文链接 */
        private String url;
        
        /** 相关度分数 */
        private Double relevance;
    }
}
