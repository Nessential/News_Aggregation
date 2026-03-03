package com.example.news.aggregation.agent.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话状态。
 * <p>
 * 保存用户会话级长期上下文，不承载单轮 FSM 执行状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionState implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话ID */
    private String sessionId;

    /** 用户ID */
    private String userId;

    /** 当前正在执行的轮次ID，用于会话内并发控制 */
    private String activeTurnId;

    /** 用户约束条件 */
    private Constraints constraints;

    /** 对话历史（用户问题 + Agent回复） */
    @Builder.Default
    private List<String> history = new ArrayList<>();

    /** 当前候选文章ID */
    @Builder.Default
    private List<Long> currentCandidates = new ArrayList<>();

    /** 检索尝试记录 */
    @Builder.Default
    private List<RetrievalAttempt> attempts = new ArrayList<>();

    /** 剩余预算（限制 LLM 调用次数） */
    @Builder.Default
    private Integer budget = 10;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 添加会话历史 */
    public void addHistory(String message) {
        if (history == null) {
            history = new ArrayList<>();
        }
        history.add(message);
        this.updatedAt = LocalDateTime.now();
    }

    /** 添加检索尝试 */
    public void addAttempt(RetrievalAttempt attempt) {
        if (attempts == null) {
            attempts = new ArrayList<>();
        }
        attempts.add(attempt);
        this.updatedAt = LocalDateTime.now();
    }

    /** 更新候选结果 */
    public void updateCandidates(List<Long> articleIds) {
        this.currentCandidates = articleIds;
        this.updatedAt = LocalDateTime.now();
    }

    /** 消耗预算 */
    public void consumeBudget(int amount) {
        this.budget = Math.max(0, this.budget - amount);
        this.updatedAt = LocalDateTime.now();
    }

    /** 检查预算是否耗尽 */
    public boolean isBudgetExhausted() {
        return budget != null && budget <= 0;
    }
}
