package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.prompt.PromptRegistry;
import com.example.news.aggregation.llm.springai.state.RouterState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 槽位提取节点。
 * 提取时间范围/语言/分类等参数。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlotExtractionNode {

    /** ChatClient 构建器 */
    private final ChatClient.Builder chatClientBuilder;
    /** 提示词仓库 */
    private final PromptRegistry promptRegistry;
    /** JSON 解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 执行槽位提取。
     *
     * @param state Router 状态
     * @return 更新后的状态
     */
    public RouterState execute(RouterState state) {
        state.incrementStep();

        String query = state.getResolvedQuery() != null ? state.getResolvedQuery() : state.getQuery();
        String sessionId = state.getSessionId() != null ? state.getSessionId() : "unknown";
        // 流程日志：进入槽位提取
        log.info("[链路最终] 进入槽位提取FLOW|router|node=slot_extract|step=start|sessionId={}|query={}|next=completeness_check",
                sessionId, truncate(query, 200));

        if (query == null) {
            log.info("槽位提取跳过-空查询FLOW|router|node=slot_extract|decision=skip_null_query|sessionId={}|next=completeness_check",
                    sessionId);
            return state;
        }

        Map<String, Object> params = state.getParams() != null ? new HashMap<>(state.getParams()) : new HashMap<>();

        try {
            ChatClient client = chatClientBuilder.build();
            String template = promptRegistry.getPrompt("intent-news", "");
            if (template == null || template.isBlank()) {
                log.warn("槽位提取缺少提示词模板: intent-news");
                applyDefaultTaskParams(state, params, "missing_prompt");
                return state;
            }
            String prompt = renderTemplate(template, query);
            String response = client.prompt()
                    .user(prompt)
                    .call()
                    .content();
            log.info("槽位提取-模型原始输出(截断)={}", truncate(response, 200));

            boolean applied = applyL2Result(response, state, params);
            if (!applied) {
                applyDefaultTaskParams(state, params, "invalid_json");
            }
        } catch (Exception e) {
            log.warn("SlotExtractionNode failed, fallback to default. error={}", e.getMessage());
            applyDefaultTaskParams(state, params, "exception");
        }

        log.info("二级意图识别结果|taskFamily={}|confidence={}|reason={}",
                state.getTaskFamily(),
                state.getTaskConfidence(),
                state.getTaskReason());
        log.info("[链路最终] 槽位提取完成FLOW|router|node=slot_extract|decision=extracted|sessionId={}|taskFamily={}|params={}|next=completeness_check",
                sessionId, state.getTaskFamily(), state.getParams());
        return state;
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

    private String renderTemplate(String template, String query) {
        if (template == null) {
            return "";
        }
        return template.replace("{{query}}", query == null ? "" : query);
    }

    private boolean applyL2Result(String raw, RouterState state, Map<String, Object> params) {
        String json = extractJson(raw);
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String taskFamily = root.path("taskFamily").asText("").trim();
            String timeRange = root.path("timeRange").asText(null);
            String startDate = root.path("startDate").asText(null);
            String endDate = root.path("endDate").asText(null);
            int topK = root.path("topK").asInt(5);
            String source = root.path("source").asText(null);
            String publisher = root.path("publisher").asText(null);
            String language = root.path("language").asText(null);
            double confidence = root.path("confidence").asDouble(0.0);
            String reason = root.path("reason").asText("");
            java.util.List<String> keywords = parseKeywords(root.path("keywords"));
            java.util.List<String> expandedKeywords = parseKeywords(root.path("expandedKeywords"));
            expandedKeywords = removeOverlap(expandedKeywords, keywords);

            if (taskFamily == null || taskFamily.isBlank()) {
                taskFamily = "QA";
            }
            state.setTaskFamily(taskFamily.toUpperCase());
            state.setTaskConfidence(confidence);
            state.setTaskReason(reason);

            putIfNotBlank(params, "timeRange", timeRange);
            putIfNotBlank(params, "time_range", timeRange);
            putIfNotBlank(params, "startDate", startDate);
            putIfNotBlank(params, "start_date", startDate);
            putIfNotBlank(params, "endDate", endDate);
            putIfNotBlank(params, "end_date", endDate);
            params.put("topK", topK);
            putIfNotBlank(params, "source", source);
            putIfNotBlank(params, "publisher", publisher);
            putIfNotBlank(params, "language", language);
            putIfNotEmpty(params, "keywords", keywords);
            putIfNotEmpty(params, "expandedKeywords", expandedKeywords);

            state.setParams(params);
            log.info("槽位提取参数|keywords={} |expandedKeywords={}",
                    keywords != null ? keywords : java.util.List.of(),
                    expandedKeywords != null ? expandedKeywords : java.util.List.of());
            return true;
        } catch (Exception e) {
            log.warn("SlotExtractionNode parse failed: {}", e.getMessage());
            return false;
        }
    }

    private void applyDefaultTaskParams(RouterState state, Map<String, Object> params, String reason) {
        state.setTaskFamily("QA");
        state.setTaskConfidence(0.0);
        state.setTaskReason(reason);
        params.putIfAbsent("topK", 5);
        state.setParams(params);
    }

    private void putIfNotBlank(Map<String, Object> params, String key, String value) {
        if (value != null && !value.isBlank()) {
            params.put(key, value);
        }
    }

    private void putIfNotEmpty(Map<String, Object> params, String key, java.util.List<String> values) {
        if (values != null && !values.isEmpty()) {
            params.put(key, values);
        }
    }

    private java.util.List<String> parseKeywords(JsonNode node) {
        if (node == null || node.isNull()) {
            return java.util.List.of();
        }
        if (node.isArray()) {
            java.util.List<String> result = new java.util.ArrayList<>();
            node.forEach(item -> {
                if (item != null && !item.isNull()) {
                    String text = item.asText("").trim();
                    if (!text.isBlank() && !result.contains(text)) {
                        result.add(text);
                    }
                }
            });
            return result;
        }
        String text = node.asText("").trim();
        if (text.isBlank()) {
            return java.util.List.of();
        }
        String[] parts = text.split("\\s*,\\s*|\\s+");
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank() && !result.contains(part)) {
                result.add(part);
            }
        }
        return result;
    }

    private java.util.List<String> removeOverlap(java.util.List<String> expandedKeywords,
                                                 java.util.List<String> keywords) {
        if (expandedKeywords == null || expandedKeywords.isEmpty()) {
            return java.util.List.of();
        }
        java.util.Set<String> keywordSet = new java.util.HashSet<>();
        if (keywords != null) {
            keywords.stream()
                    .filter(item -> item != null && !item.isBlank())
                    .forEach(item -> keywordSet.add(item.trim().toLowerCase()));
        }

        java.util.List<String> filtered = new java.util.ArrayList<>();
        for (String item : expandedKeywords) {
            if (item == null || item.isBlank()) {
                continue;
            }
            String normalized = item.trim().toLowerCase();
            if (keywordSet.contains(normalized)) {
                continue;
            }
            if (!filtered.contains(item)) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private String extractJson(String raw) {
        if (raw == null) {
            return null;
        }
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
