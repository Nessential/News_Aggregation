package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.contract.GeneratorDraft;
import com.example.news.aggregation.llm.springai.state.GeneratorState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 质量检查节点
 * 根据评分与引用情况决定是否重试
 */
@Slf4j
@Component
public class QualityCheckNode {

    /**
     * 执行质量检查
     *
     * @param state Generator状态
     * @return 更新后的状态
     */
    public GeneratorState execute(GeneratorState state) {
        state.incrementStep();

        if (Boolean.TRUE.equals(state.getAllowNoEvidence())) {
            state.setValidated(true);
            return state;
        }

        GeneratorDraft draft = state.getDraft();

        double score = state.getQualityScore() != null ? state.getQualityScore() : 0.0;

        if (draft == null || draft.getAnswerItems() == null || draft.getAnswerItems().isEmpty()) {
            return markRetryOrPass(state, score);
        }

        boolean hasLinkedNews = draft.getAnswerItems().stream()
                .anyMatch(item -> item != null
                        && item.getNewsIds() != null
                        && item.getNewsIds().stream().anyMatch(id -> id != null && !id.isBlank()));

        boolean pass = score >= 0.6 && hasLinkedNews;
        if (pass) {
            state.setValidated(true);
            return state;
        }

        return markRetryOrPass(state, score);
    }

    private GeneratorState markRetryOrPass(GeneratorState state, double score) {
        int retryCount = state.getRetryCount();
        int maxRetries = state.getMaxRetries();
        if (retryCount < maxRetries) {
            state.setRetryCount(retryCount + 1);
            state.setValidated(false);
        } else {
            // 达到最大重试次数，强制通过并记录告警
            log.warn("QualityCheckNode reached max retries, force pass. score={}", score);
            state.setValidated(true);
        }
        return state;
    }
}
