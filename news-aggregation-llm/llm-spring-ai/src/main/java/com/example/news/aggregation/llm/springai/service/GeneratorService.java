package com.example.news.aggregation.llm.springai.service;

import com.example.news.aggregation.llm.springai.config.GraphProperties;
import com.example.news.aggregation.llm.springai.contract.GeneratorDraft;
import com.example.news.aggregation.llm.springai.graph.GeneratorGraph;
import com.example.news.aggregation.llm.springai.state.GeneratorState;
import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import com.example.news.aggregation.llm.springai.validator.OutputValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 生成服务
 * 封装GeneratorGraph调用与重试逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeneratorService {

    private final GeneratorGraph generatorGraph;
    private final OutputValidator validator;
    private final GraphProperties graphProperties;

    /**
     * 生成答案草稿
     *
     * @param query 用户查询
     * @param taskFamily 任务类型
     * @param evidence 证据列表
     * @return 生成草稿
     */
    public GeneratorDraft generate(String query, String taskFamily, List<RetrievalResult> evidence, String retrievalMode) {
        // 配置关闭GeneratorGraph时直接降级
        if (!graphProperties.isGeneratorEnabled()) {
            return GeneratorDraft.conservative("生成能力未启用");
        }

        int maxRetries = graphProperties.getMaxIterations();
        boolean allowNoEvidence = "NONE".equalsIgnoreCase(retrievalMode);

        GeneratorState state = GeneratorState.builder()
                .query(query)
                .taskFamily(taskFamily)
                .retrievalMode(retrievalMode)
                .allowNoEvidence(allowNoEvidence)
                .evidence(evidence)
                .maxRetries(maxRetries)
                .retryCount(0)
                .build();

        try {
            // Graph 内部处理循环与重试，外层只负责一次调用
            GeneratorState finalState = generatorGraph.invoke(state);

            GeneratorDraft draft = finalState.getDraft();
            if (draft == null) {
                return GeneratorDraft.conservative("暂无可用答案");
            }

            // 同步质量评分
            draft.setQualityScore(finalState.getQualityScore());

            if (allowNoEvidence) {
                if (draft.getAnswer() == null || draft.getAnswer().isBlank()) {
                    return GeneratorDraft.conservative("暂无可用答案");
                }
                return draft;
            }

            if (!validator.validate(draft)) {
                log.warn("GeneratorDraft validation failed, fallback to conservative answer.");
                return GeneratorDraft.conservative("证据不足或质量不足");
            }

            return draft;
        } catch (Exception e) {
            log.error("GeneratorService generate failed.", e);
            return GeneratorDraft.conservative("生成过程中发生错误");
        }
    }

    public GeneratorDraft generate(String query, String taskFamily, List<RetrievalResult> evidence) {
        return generate(query, taskFamily, evidence, null);
    }
}
