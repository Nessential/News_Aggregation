package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.contract.GeneratorDraft;
import com.example.news.aggregation.llm.springai.state.GeneratorState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

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

        GeneratorDraft draft = state.getDraft();
        if (draft == null) {
            state.setValidated(false);
            return state;
        }

        double score = state.getQualityScore() != null ? state.getQualityScore() : 0.0;
        List<GeneratorDraft.Citation> citations = draft.getCitations();

        boolean hasCitations = citations != null && !citations.isEmpty();
        boolean pass = score >= 0.6 && hasCitations;

        if (pass) {
            state.setValidated(true);
            return state;
        }

        // 未通过，检查是否可重试
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