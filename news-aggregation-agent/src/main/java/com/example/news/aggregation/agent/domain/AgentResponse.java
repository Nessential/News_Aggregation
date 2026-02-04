package com.example.news.aggregation.agent.domain;

import com.example.news.aggregation.agent.enums.TaskFamily;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent响应
 * 统一的Agent输出格式，包含答案、候选文章、引用等
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * 会话ID
     */
    private String sessionId;
    
    /**
     * 生成的答案文本
     */
    private String answer;
    
    /**
     * 候选文章列表
     */
    private List<Candidate> candidates;
    
    /**
     * 引用信息 (文章ID -> 引用片段)
     */
    private List<String> citations;
    
    /**
     * 任务类型
     */
    private TaskFamily taskFamily;
    
    /**
     * 是否需要追问
     */
    private Boolean needsClarification;
    
    /**
     * 追问建议 (如果需要追问)
     */
    private String clarificationPrompt;
    
    /**
     * 响应时间戳
     */
    private LocalDateTime timestamp;
    
    /**
     * 执行耗时 (毫秒)
     */
    private Long executionTimeMs;
    
    /**
     * 元数据 (调试信息)
     */
    private ResponseMetadata metadata;
    
    /**
     * 响应元数据
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponseMetadata implements Serializable {
        private static final long serialVersionUID = 1L;
        
        /**
         * 检索到的文档数量
         */
        private Integer retrievedCount;
        
        /**
         * LLM调用次数
         */
        private Integer llmCallCount;
        
        /**
         * 使用的Pipeline类型
         */
        private String pipelineType;
        
        /**
         * 剩余预算
         */
        private Integer remainingBudget;
    }
}
