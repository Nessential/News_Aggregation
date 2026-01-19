package com.example.news.aggregation.core.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FSM状态机上下文
 * <p>
 * 存储状态转换所需的所有数据
 *
 * @author agent
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FSMContext implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * Session状态 (从Redis加载)
     */
    private SessionState sessionState;

    /**
     * Router结果
     */
    private Object routerResult;  // RouterResult from contract layer

    /**
     * Plan结果
     */
    private Object plan;  // Plan from contract layer

    /**
     * 检索候选集
     */
    private Object candidates;  // CandidateSet

    /**
     * 证据包
     */
    private Object evidencePack;  // EvidencePack

    /**
     * 生成草稿
     */
    private Object draft;  // GeneratorDraft from contract layer

    /**
     * 重试计数器
     */
    @Builder.Default
    private int retryCount = 0;

    /**
     * 证据不足恢复尝试次数
     */
    @Builder.Default
    private int evidenceRetryCount = 0;

    /**
     * 元数据 (用于存储临时数据)
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * 错误信息 (如果有)
     */
    private String errorMessage;

    /**
     * 是否需要用户确认
     */
    @Builder.Default
    private boolean requiresConfirmation = false;

    /**
     * 用户确认结果
     */
    private Boolean userConfirmed;

    // ==================== 辅助方法 ====================

    /**
     * 检查是否需要追问
     */
    public boolean needsClarify() {
        // TODO: 根据routerResult判断
        return false;
    }

    /**
     * 检查是否是复杂任务
     */
    public boolean isComplexTask() {
        // TODO: 根据routerResult的taskFamily判断
        return false;
    }

    /**
     * 检查候选是否为空
     */
    public boolean hasCandidates() {
        return candidates != null;
    }

    /**
     * 检查证据是否达标
     */
    public boolean hasEvidenceMet() {
        // TODO: 根据evidencePack判断
        return evidencePack != null;
    }

    /**
     * 检查是否还能重试
     */
    public boolean canRetryEvidence() {
        return evidenceRetryCount < 3;
    }

    /**
     * 检查是否需要Rerank
     */
    public boolean needsRerank() {
        // TODO: 根据配置或候选数量判断
        return false;
    }

    /**
     * 检查是否需要Canonicalize
     */
    public boolean needsCanonicalize() {
        // TODO: 根据taskFamily判断 (Discovery/Digest/Timeline/Compare)
        return false;
    }

    /**
     * 检查是否有写操作
     */
    public boolean hasWriteOperation() {
        // TODO: 根据draft的suggestedActions判断
        return false;
    }

    /**
     * 增加重试计数
     */
    public void incrementRetry() {
        this.retryCount++;
    }

    /**
     * 增加证据重试计数
     */
    public void incrementEvidenceRetry() {
        this.evidenceRetryCount++;
    }
}
