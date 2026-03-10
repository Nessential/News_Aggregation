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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class DraftGenerateNode {

    private final ChatClient.Builder chatClientBuilder;
    private final PromptRegistry promptRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GeneratorState execute(GeneratorState state) {
        state.incrementStep();

        String query = state.getQuery();
        String queryInterpretation = state.getQueryInterpretation();
        String effectiveQuery = (queryInterpretation != null && !queryInterpretation.isBlank()) ? queryInterpretation : query;
        String taskFamily = state.getTaskFamily() != null ? state.getTaskFamily() : "QA";
        List<RetrievalResult> evidence = state.getEvidence();
        boolean allowNoEvidence = Boolean.TRUE.equals(state.getAllowNoEvidence());

        try {
            String context = buildContext(evidence, allowNoEvidence);
            String prompt = buildTaskPrompt(effectiveQuery, context, taskFamily, allowNoEvidence);

            ChatClient client = chatClientBuilder.build();
            String raw = client.prompt().user(prompt).call().content();
            log.info("generator raw output sample={}", truncate(raw, 500));

            GeneratorDraft draft = parseJsonDraft(raw);
            int itemCount = draft.getAnswerItems() != null ? draft.getAnswerItems().size() : 0;
            int linkedNewsCount = draft.getAnswerItems() == null ? 0 : draft.getAnswerItems().stream()
                    .mapToInt(item -> item.getNewsIds() == null ? 0 : item.getNewsIds().size())
                    .sum();
            log.info("generator parsed draft done|itemCount={}|linkedNewsCount={}", itemCount, linkedNewsCount);
            state.setDraft(draft);
        } catch (Exception e) {
            log.warn("DraftGenerateNode failed, fallback to empty answer items. error={}", e.getMessage());
            state.setDraft(GeneratorDraft.builder().answerItems(List.of()).build());
        }

        return state;
    }

    private String buildContext(List<RetrievalResult> evidence, boolean allowNoEvidence) {
        if (evidence == null || evidence.isEmpty()) {
            return allowNoEvidence ? "" : "无可用证据。";
        }
        return evidence.stream()
                .filter(r -> r.getContent() != null && !r.getContent().isBlank())
                .map(r -> {
                    String id = r.getId() != null ? r.getId() : "";
                    String publishedAt = r.getPublishedAt() != null ? " [" + r.getPublishedAt() + "]" : "";
                    return String.format("[%s%s] %s", id, publishedAt, r.getContent());
                })
                .collect(Collectors.joining("\n\n"));
    }

    private String buildTaskPrompt(String query, String context, String taskFamily, boolean allowNoEvidence) {
        String safeQuery = query == null ? "" : query;
        String safeContext = context == null ? "" : context;

        String base;
        if (allowNoEvidence) {
            base = promptRegistry.getPrompt("generate-direct", "");
        } else {
            base = switch (taskFamily) {
                case "SUMMARY" -> promptRegistry.getPrompt("generate-summary", "");
                case "COMPARE" -> promptRegistry.getPrompt("generate-compare", "");
                case "TIMELINE" -> promptRegistry.getPrompt("generate-timeline", "");
                case "DEEP_DIVE" -> promptRegistry.getPrompt("generate-deep-dive", "");
                default -> promptRegistry.getPrompt("generate-qa", "");
            };
        }
        if (base == null || base.isBlank()) {
            base = "";
        }

        String rendered = base
                .replace("{{query}}", safeQuery)
                .replace("{{context}}", safeContext);

        // Hard schema guard: only new structured format is accepted.
        String schemaInstruction = "\n\n仅输出严格JSON，不要输出任何额外文本。输出格式必须是:\n"
                + "{\n"
                + "  \"answerItems\": [\n"
                + "    {\"text\": \"单条回答\", \"newsIds\": [\"76\", \"102\"]}\n"
                + "  ]\n"
                + "}\n"
                + "要求:\n"
                + "1) answerItems 至少1条;\n"
                + "2) 每条 text 不能为空;\n"
                + "3) newsIds 必须是字符串ID数组; 若确实无证据可填空数组;\n"
                + "4) text 内容中合理换行，使用换行符\\n分隔，每段不宜过长。";
        return rendered + schemaInstruction;
    }

    private GeneratorDraft parseJsonDraft(String raw) {
        if (raw == null || raw.isBlank()) {
            return GeneratorDraft.builder().answerItems(List.of()).build();
        }

        String json = extractJson(raw);
        if (json == null || json.isBlank()) {
            log.warn("DraftGenerateNode output is not valid JSON.");
            return GeneratorDraft.builder().answerItems(List.of()).build();
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            List<GeneratorDraft.AnswerItem> answerItems = parseAnswerItems(root.path("answerItems"));
            return GeneratorDraft.builder().answerItems(answerItems).build();
        } catch (Exception e) {
            log.warn("DraftGenerateNode JSON parse failed: {}", e.getMessage());
            return GeneratorDraft.builder().answerItems(List.of()).build();
        }
    }

    private List<GeneratorDraft.AnswerItem> parseAnswerItems(JsonNode node) {
        List<GeneratorDraft.AnswerItem> items = new ArrayList<>();
        if (node == null || node.isMissingNode() || !node.isArray()) {
            return items;
        }

        for (JsonNode itemNode : node) {
            if (itemNode == null || !itemNode.isObject()) {
                continue;
            }
            String text = itemNode.path("text").asText("").trim();
            if (text.isBlank()) {
                continue;
            }
            List<String> newsIds = parseNewsIds(itemNode.path("newsIds"));
            items.add(GeneratorDraft.AnswerItem.builder().text(text).newsIds(newsIds).build());
        }
        return items;
    }

    private List<String> parseNewsIds(JsonNode node) {
        List<String> ids = new ArrayList<>();
        if (node == null || node.isMissingNode() || !node.isArray()) {
            return ids;
        }
        for (JsonNode idNode : node) {
            String id = idNode == null ? "" : idNode.asText("").trim();
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
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

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
