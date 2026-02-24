package com.example.news.aggregation.agent.workflow.executor;

import com.example.news.aggregation.agent.client.GeneratorClient;
import com.example.news.aggregation.agent.workflow.CapabilityExecutor;
import com.example.news.aggregation.agent.workflow.CapabilityMetadata;
import com.example.news.aggregation.agent.workflow.WorkflowContext;
import com.example.news.aggregation.llm.springai.contract.GeneratorDraft;
import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LLM 生成能力。
 * 支持摘要/对比/分析/时间线/问答。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmGenerateExecutor implements CapabilityExecutor {

    private final GeneratorClient generatorClient;

    @Override
    public String capabilityName() {
        return "llm_generate";
    }

    @Override
    public CapabilityMetadata metadata() {
        return CapabilityMetadata.builder()
                .name("llm_generate")
                .version("v1")
                .description("基于证据生成答案")
                .timeoutMs(15000L)
                .costLevel("HIGH")
                .permissionScope("INTERNAL")
                .build();
    }

    @Override
    public Object execute(Map<String, Object> parameters, WorkflowContext context) {
        String taskFamily = parameters != null && parameters.get("taskFamily") != null
                ? String.valueOf(parameters.get("taskFamily"))
                : context.getTaskFamily();

        List<RetrievalResult> evidence = convertEvidence(context.getEvidence());
        GeneratorDraft draft = generatorClient.generate(context.getQuery(), taskFamily, evidence);
        if (draft == null || draft.getAnswer() == null || draft.getAnswer().isBlank()) {
            String fallback = "生成失败或证据不足。";
            context.putAttribute("answer", fallback);
            log.warn("llm_generate fallback, empty draft.");
            return fallback;
        }

        context.putAttribute("answer", draft.getAnswer());
        context.putAttribute("citations", draft.getCitations());
        log.info("llm_generate completed, answer length={}", draft.getAnswer().length());
        return draft.getAnswer();
    }

    private List<RetrievalResult> convertEvidence(List<com.example.news.aggregation.agent.tool.dto.RetrievalResult> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        return evidence.stream()
                .map(item -> RetrievalResult.builder()
                        .id(item.getArticleId() != null ? String.valueOf(item.getArticleId()) : "")
                        .title("")
                        .content(item.getMatchedSnippet() != null ? item.getMatchedSnippet() : "")
                        .url("")
                        .score(item.getScore() != null ? item.getScore() : 0.0)
                        .source("AGENT")
                        .build())
                .collect(Collectors.toList());
    }
}