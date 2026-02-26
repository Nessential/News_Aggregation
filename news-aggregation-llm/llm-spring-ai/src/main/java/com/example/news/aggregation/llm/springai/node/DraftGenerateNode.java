package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.contract.GeneratorDraft;
import com.example.news.aggregation.llm.springai.prompt.PromptRegistry;
import com.example.news.aggregation.llm.springai.state.GeneratorState;
import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 草稿生成节点
 * 基于证据包生成初稿
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DraftGenerateNode {

    /** ChatClient构建器 */
    private final ChatClient.Builder chatClientBuilder;
    /** 提示词仓库 */
    private final PromptRegistry promptRegistry;
    /** JSON解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 执行草稿生成
     *
     * @param state Generator状态
     * @return 更新后的状态
     */
    public GeneratorState execute(GeneratorState state) {
        state.incrementStep();

        String query = state.getQuery();
        String taskFamily = state.getTaskFamily() != null ? state.getTaskFamily() : "QA";
        List<RetrievalResult> evidence = state.getEvidence();
        boolean allowNoEvidence = Boolean.TRUE.equals(state.getAllowNoEvidence());

        try {
            String context = buildContext(evidence, allowNoEvidence);
            String prompt = buildTaskPrompt(query, context, taskFamily, allowNoEvidence);

            ChatClient client = chatClientBuilder.build();
            String raw = client.prompt()
                    .user(prompt)
                    .call()
                    .content();
            log.info("生成-模型原始输出(截断)={}", truncate(raw, 500));

            GeneratorDraft draft = parseJsonDraft(raw, allowNoEvidence);

            state.setDraft(draft);
        } catch (Exception e) {
            log.warn("DraftGenerateNode failed, fallback to empty answer. error={}", e.getMessage());
            state.setDraft(GeneratorDraft.builder().answer("").build());
        }

        return state;
    }

    private String buildContext(List<RetrievalResult> evidence, boolean allowNoEvidence) {
        if (evidence == null || evidence.isEmpty()) {
            return allowNoEvidence ? "" : "无可用证据。";
        }
        return evidence.stream()
                .map(r -> String.format("[%s] %s: %s", r.getId(), r.getTitle(), r.getContent()))
                .collect(Collectors.joining("\n\n"));
    }

    private String buildTaskPrompt(String query, String context, String taskFamily, boolean allowNoEvidence) {
        String safeQuery = query == null ? "" : query;
        String safeContext = context == null ? "" : context;
        if (allowNoEvidence) {
            String template = promptRegistry.getPrompt("generate-direct", "");
            if (template == null || template.isBlank()) {
                log.warn("DraftGenerateNode missing prompt template: generate-direct");
                return "";
            }
            return renderTemplate(template, safeQuery, safeContext);
        }
        switch (taskFamily) {
            case "SUMMARY":
                return renderTemplateOrEmpty("generate-summary", safeQuery, safeContext);
            case "COMPARE":
                return renderTemplateOrEmpty("generate-compare", safeQuery, safeContext);
            case "TIMELINE":
                return renderTemplateOrEmpty("generate-timeline", safeQuery, safeContext);
            case "DEEP_DIVE":
                return renderTemplateOrEmpty("generate-deep-dive", safeQuery, safeContext);
            case "QA":
            default:
                return renderTemplateOrEmpty("generate-qa", safeQuery, safeContext);
        }
    }

    private String renderTemplate(String template, String query, String context) {
        if (template == null) {
            return "";
        }
        String safeQuery = query == null ? "" : query;
        String safeContext = context == null ? "" : context;
        return template
                .replace("{{query}}", safeQuery)
                .replace("{{context}}", safeContext);
    }

    private String renderTemplateOrEmpty(String key, String query, String context) {
        String template = promptRegistry.getPrompt(key, "");
        if (template == null || template.isBlank()) {
            log.warn("DraftGenerateNode missing prompt template: {}", key);
            return "";
        }
        return renderTemplate(template, query, context);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    private GeneratorDraft parseJsonDraft(String raw, boolean allowNoEvidence) {
        if (raw == null || raw.isBlank()) {
            return GeneratorDraft.builder().answer("").build();
        }
        String json = extractJson(raw);
        if (json == null || json.isBlank()) {
            log.warn("DraftGenerateNode output is not valid JSON.");
            return GeneratorDraft.builder().answer("").build();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String answer = root.path("answer").asText("");
            List<GeneratorDraft.Citation> citations = parseCitations(root.path("citations"));
            if (allowNoEvidence && citations.isEmpty()) {
                return GeneratorDraft.builder().answer(answer).citations(citations).build();
            }
            return GeneratorDraft.builder().answer(answer).citations(citations).build();
        } catch (Exception e) {
            log.warn("DraftGenerateNode JSON parse failed: {}", e.getMessage());
            return GeneratorDraft.builder().answer("").build();
        }
    }

    private List<GeneratorDraft.Citation> parseCitations(JsonNode citationsNode) {
        List<GeneratorDraft.Citation> citations = new java.util.ArrayList<>();
        if (citationsNode == null || citationsNode.isMissingNode() || !citationsNode.isArray()) {
            return citations;
        }
        int position = 0;
        for (JsonNode item : citationsNode) {
            String sourceId = null;
            String text = null;
            if (item.isTextual()) {
                sourceId = item.asText();
            } else if (item.isObject()) {
                sourceId = item.path("sourceId").asText("");
                if (sourceId.isBlank()) {
                    sourceId = item.path("id").asText("");
                }
                text = item.path("text").asText(null);
            }
            if (sourceId == null || sourceId.isBlank()) {
                continue;
            }
            citations.add(GeneratorDraft.Citation.builder()
                    .sourceId(sourceId)
                    .text(text)
                    .position(position++)
                    .build());
        }
        return citations;
    }

    private String extractJson(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return null;
    }
}
