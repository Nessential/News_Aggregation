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
 * Agent 统一响应体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 会话ID */
    private String sessionId;
    /** 轮次ID */
    private String turnId;
    /** 轮次状态，例如 DONE/FAILED/BUSY */
    private String turnStatus;
    /** 错误码，例如 SESSION_BUSY */
    private String errorCode;
    /** 当前占用会话锁的轮次ID */
    private String runningTurnId;

    /** 生成的答案文本 */
    private String answer;
    /** 候选文章列表 */
    private List<Candidate> candidates;
    /** 引用信息（文章ID列表） */
    private List<String> citations;
    /** 任务类型 */
    private TaskFamily taskFamily;
    /** 是否需要追问 */
    private Boolean needsClarification;
    /** 追问提示 */
    private String clarificationPrompt;
    /** 响应时间 */
    private LocalDateTime timestamp;
    /** 执行耗时（毫秒） */
    private Long executionTimeMs;
    /** 响应元数据 */
    private ResponseMetadata metadata;

    /**
     * 响应元数据。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponseMetadata implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 检索文档数量 */
        private Integer retrievedCount;
        /** LLM 调用次数 */
        private Integer llmCallCount;
        /** 执行链路类型 */
        private String pipelineType;
        /** 会话剩余预算 */
        private Integer remainingBudget;
    }
}

