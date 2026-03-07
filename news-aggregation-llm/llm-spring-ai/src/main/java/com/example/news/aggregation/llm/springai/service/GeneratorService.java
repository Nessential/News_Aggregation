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

@Slf4j
@Service
@RequiredArgsConstructor
public class GeneratorService {

    private final GeneratorGraph generatorGraph;
    private final OutputValidator validator;
    private final GraphProperties graphProperties;

    public GeneratorDraft generate(String query, String queryInterpretation, String taskFamily, List<RetrievalResult> evidence, String retrievalMode) {
        if (!graphProperties.isGeneratorEnabled()) {
            return GeneratorDraft.conservative("生成能力未启用");
        }

        int evidenceCount = evidence != null ? evidence.size() : 0;
        long nonEmptyContentCount = evidence != null ? evidence.stream()
                .filter(e -> e.getContent() != null && !e.getContent().isBlank())
                .count() : 0;
        log.info("[LLM-Generator] request|query={}|queryInterpretation={}|taskFamily={}|evidenceCount={}|nonEmptyContentCount={}|retrievalMode={}",
                query, queryInterpretation, taskFamily, evidenceCount, nonEmptyContentCount, retrievalMode);

        int maxRetries = graphProperties.getMaxIterations();
        boolean allowNoEvidence = "NONE".equalsIgnoreCase(retrievalMode);

        GeneratorState state = GeneratorState.builder()
                .query(query)
                .queryInterpretation(queryInterpretation)
                .taskFamily(taskFamily)
                .retrievalMode(retrievalMode)
                .allowNoEvidence(allowNoEvidence)
                .evidence(evidence)
                .maxRetries(maxRetries)
                .retryCount(0)
                .build();

        try {
            GeneratorState finalState = generatorGraph.invoke(state);
            GeneratorDraft draft = finalState.getDraft();
            if (draft == null) {
                return GeneratorDraft.conservative("暂无可用答案");
            }

            draft.setQualityScore(finalState.getQualityScore());

            if (allowNoEvidence) {
                if (!hasAnyTextAnswerItem(draft)) {
                    return GeneratorDraft.conservative("暂无可用答案");
                }
                return draft;
            }

            if (!validator.validate(draft)) {
                int itemCount = draft.getAnswerItems() != null ? draft.getAnswerItems().size() : 0;
                Double qualityScore = draft.getQualityScore();
                log.warn("GeneratorDraft validate failed|evidenceCount={} |itemCount={} |qualityScore={}",
                        evidenceCount, itemCount, qualityScore);
                if (evidenceCount > 0 && hasAnyTextAnswerItem(draft)) {
                    log.warn("evidence exists and answerItems have text, keep best-effort draft");
                    return draft;
                }
                return GeneratorDraft.conservative("证据不足或质量不足");
            }

            return draft;
        } catch (Exception e) {
            log.error("GeneratorService generate failed.", e);
            return GeneratorDraft.conservative("生成过程中发生错误");
        }
    }

    public GeneratorDraft generate(String query, String queryInterpretation, String taskFamily, List<RetrievalResult> evidence) {
        return generate(query, queryInterpretation, taskFamily, evidence, null);
    }

    public GeneratorDraft generate(String query, String taskFamily, List<RetrievalResult> evidence) {
        return generate(query, null, taskFamily, evidence, null);
    }

    private boolean hasAnyTextAnswerItem(GeneratorDraft draft) {
        if (draft == null || draft.getAnswerItems() == null || draft.getAnswerItems().isEmpty()) {
            return false;
        }
        return draft.getAnswerItems().stream()
                .anyMatch(item -> item != null && item.getText() != null && !item.getText().isBlank());
    }
}
