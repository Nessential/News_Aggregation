package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.intent.IntentRuleLoader;
import com.example.news.aggregation.llm.springai.intent.IntentRuleSet;
import com.example.news.aggregation.llm.springai.prompt.PromptRegistry;
import com.example.news.aggregation.llm.springai.state.RouterState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 意图分析节点。
 * 识别任务族、检索模式与风险等级。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentAnalyzeNode {

    /** ChatClient 构建器 */
    private final ChatClient.Builder chatClientBuilder;
    /** 意图规则加载器 */
    private final IntentRuleLoader intentRuleLoader;
    /** 提示词仓库 */
    private final PromptRegistry promptRegistry;
    /** JSON 解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 执行意图分析。
     *
     * @param state Router 状态
     * @return 更新后的状态
     */
    public RouterState execute(RouterState state) {
        state.incrementStep();

        String query = state.getQuery() == null ? "" : state.getQuery();
        String sessionId = state.getSessionId() != null ? state.getSessionId() : "unknown";
        // 流程日志：进入意图分析节点
        log.info("[链路最终] 进入意图分析节点FLOW|router|node=intent_analyze|step=start|sessionId={}|query={}|next=reference_resolve",
                sessionId, truncate(query, 200));

        IntentRuleSet ruleSet = intentRuleLoader.getRuleSet();
        String decisionSource = "llm";
        String decisionReason = "llm_response";
        String responseSnippet = "";

        try {
            ChatClient client = chatClientBuilder.build();
            String promptKey = ruleSet != null && ruleSet.getPromptKey() != null
                    ? ruleSet.getPromptKey()
                    : "intent-analysis";
            String template = promptRegistry.getPrompt(promptKey, "");
            if (template == null || template.isBlank()) {
                log.warn("IntentAnalyzeNode missing prompt template, fallback to default.");
                setDefaultNewsRoute(state, "missing_prompt_template");
                return state;
            }
            String prompt = renderPrompt(template, query);

            String response = client.prompt()
                    .user(prompt)
                    .call()
                    .content();
            responseSnippet = truncate(response, 200);
            log.info("意图识别-模型原始输出(截断)={}", responseSnippet);

            applyL1Result(response, state);
            log.info("一级意图识别结果|intentScope={}|retrievalMode={}|confidence={}|reason={}",
                    state.getIntentScope(),
                    state.getRetrievalMode(),
                    state.getIntentConfidence(),
                    state.getIntentReason());
        } catch (Exception e) {
            log.warn("IntentAnalyzeNode failed, fallback to rule-based. error={}", e.getMessage());
            decisionSource = "fallback";
            decisionReason = "exception -> " + e.getMessage();
            setDefaultNewsRoute(state, decisionReason);
        }

        // 流程日志：输出意图识别结果与理由
        log.info("[链路最终] 意图识别结果FLOW|router|node=intent_analyze|decisionSource={}|reason={}|intentScope={}|retrievalMode={}|confidence={}|llmResponse={}|next=reference_resolve",
                decisionSource,
                decisionReason,
                state.getIntentScope(),
                state.getRetrievalMode(),
                state.getIntentConfidence(),
                responseSnippet);

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

    private String renderPrompt(String template, String query) {
        if (template == null) {
            return "";
        }
        return template.replace("{{query}}", query == null ? "" : query);
    }

    private void applyL1Result(String raw, RouterState state) {
        String json = extractJson(raw);
        if (json == null || json.isBlank()) {
            setDefaultNewsRoute(state, "invalid_json");
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String intentScope = root.path("intentScope").asText("").trim();
            String retrievalMode = root.path("retrievalMode").asText("").trim();
            double confidence = root.path("confidence").asDouble(0.0);
            String reason = root.path("reason").asText("");
            String language = root.path("language").asText("").trim();
            JsonNode entitiesNode = root.path("entities");
            if (entitiesNode.isArray()) {
                java.util.List<String> entitiesList = new java.util.ArrayList<>();
                for (JsonNode entity : entitiesNode) {
                    String e = entity.asText("");
                    if (!e.isBlank()) {
                        entitiesList.add(e.trim());
                    }
                }
                state.setEntities(entitiesList);
                log.info("意图识别-提取实体|entities={}", entitiesList);
            }

            if (intentScope.isBlank()) {
                setDefaultNewsRoute(state, "missing_intent_scope");
                return;
            }
            state.setIntentScope(intentScope.toUpperCase(Locale.ROOT));
            String normalizedMode = retrievalMode.isBlank() ? "HYBRID" : retrievalMode.toUpperCase(Locale.ROOT);
            if ("NEWS".equalsIgnoreCase(intentScope) && "NONE".equalsIgnoreCase(normalizedMode)) {
                normalizedMode = "HYBRID";
            }
            state.setRetrievalMode(normalizedMode);
            state.setIntentConfidence(confidence);
            state.setIntentReason(reason);
            if (!language.isBlank()) {
                ensureParams(state).put("language", language.toLowerCase(Locale.ROOT));
            }

            if ("NON_NEWS".equalsIgnoreCase(intentScope)) {
                state.setTaskFamily("QA");
                state.setRiskLevel("LOW");
                state.setNeedsClarification(false);
            }
        } catch (Exception e) {
            setDefaultNewsRoute(state, "parse_error");
        }
    }

    private void setDefaultNewsRoute(RouterState state, String reason) {
        state.setIntentScope("NEWS");
        state.setRetrievalMode("HYBRID");
        state.setIntentConfidence(0.0);
        state.setIntentReason(reason);
        state.setTaskFamily("QA");
        state.setRiskLevel("LOW");
        state.setNeedsClarification(false);
    }

    private java.util.Map<String, Object> ensureParams(RouterState state) {
        if (state.getParams() == null) {
            state.setParams(new java.util.HashMap<>());
        }
        return state.getParams();
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
