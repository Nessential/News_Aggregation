package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.state.RouterState;
import com.example.news.aggregation.llm.springai.intent.IntentRuleLoader;
import com.example.news.aggregation.llm.springai.intent.IntentRuleSet;
import com.example.news.aggregation.llm.springai.prompt.PromptRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
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
        log.info("进入意图分析节点FLOW|router|node=intent_analyze|step=start|sessionId={}|query={}|next=reference_resolve",
                sessionId, truncate(query, 200));

        IntentRuleSet ruleSet = intentRuleLoader.getRuleSet();
        RuleDecision ruleDecision = ruleTaskFamilyDecision(query, ruleSet);
        if (isDirectAnswerQuery(query, ruleSet)) {
            state.setTaskFamily("QA");
            state.setRetrievalMode("NONE");
            state.setRiskLevel("LOW");
            log.info("直答判定FLOW|router|node=intent_analyze|decisionSource=rule_direct_answer|reason=算术/常识问题|taskFamily=QA|retrievalMode=NONE|riskLevel=LOW|next=reference_resolve");
            return state;
        }
        String decisionSource = "llm";
        String decisionReason = "llm_response";
        String responseSnippet = "";

        try {
            ChatClient client = chatClientBuilder.build();
            String promptTemplate = ruleSet != null ? ruleSet.getPromptTemplate() : null;
            String promptKey = ruleSet != null ? ruleSet.getPromptKey() : null;
            if (promptKey == null || promptKey.isBlank()) {
                promptKey = "intent-analysis";
            }
            String resolvedTemplate = (promptTemplate != null && !promptTemplate.isBlank())
                    ? promptTemplate
                    : promptRegistry.getPrompt(promptKey, "");
            if (resolvedTemplate == null || resolvedTemplate.isBlank()) {
                log.warn("IntentAnalyzeNode missing prompt template, fallback to rule-based.");
                decisionSource = "rule_fallback";
                decisionReason = "missing_prompt_template";
                state.setTaskFamily(ruleDecision.taskFamily());
                state.setRetrievalMode("HYBRID");
                state.setRiskLevel("LOW");
                return state;
            }
            String prompt = renderPrompt(resolvedTemplate, query);

            String response = client.prompt()
                    .user(prompt)
                    .call()
                    .content();
            responseSnippet = truncate(response, 200);
            log.info("意图识别-模型原始输出(截断)={}", responseSnippet);

            TaskFamilyDecision taskFamilyDecision = extractTaskFamilyDecision(response, ruleDecision);
            state.setTaskFamily(taskFamilyDecision.taskFamily());
            state.setRetrievalMode(extractRetrievalMode(response));
            state.setRiskLevel(extractRiskLevel(response));

            if (!"llm".equals(taskFamilyDecision.source())) {
                decisionSource = taskFamilyDecision.source();
                decisionReason = taskFamilyDecision.reason();
            }
        } catch (Exception e) {
            log.warn("IntentAnalyzeNode failed, fallback to rule-based. error={}", e.getMessage());
            decisionSource = "rule_fallback";
            decisionReason = "exception -> " + e.getMessage();
            state.setTaskFamily(ruleDecision.taskFamily());
            state.setRetrievalMode("HYBRID");
            state.setRiskLevel("LOW");
        }

        // 流程日志：输出意图识别结果与理由
        log.info("意图识别结果FLOW|router|node=intent_analyze|decisionSource={}|reason={}|taskFamily={}|retrievalMode={}|riskLevel={}|llmResponse={}|next=reference_resolve",
                decisionSource,
                decisionReason,
                state.getTaskFamily(),
                state.getRetrievalMode(),
                state.getRiskLevel(),
                responseSnippet);

        return state;
    }

    private TaskFamilyDecision extractTaskFamilyDecision(String response, RuleDecision ruleDecision) {
        if (response == null) {
            return new TaskFamilyDecision(ruleDecision.taskFamily(), "rule_fallback", ruleDecision.reason());
        }
        String cleaned = response.toUpperCase().replaceAll("[^A-Z_]", "");
        if (cleaned.contains("SUMMARY")) return new TaskFamilyDecision("SUMMARY", "llm", "llm_response");
        if (cleaned.contains("COMPARE")) return new TaskFamilyDecision("COMPARE", "llm", "llm_response");
        if (cleaned.contains("TIMELINE")) return new TaskFamilyDecision("TIMELINE", "llm", "llm_response");
        if (cleaned.contains("DEEPDIVE") || cleaned.contains("DEEP_DIVE")) {
            return new TaskFamilyDecision("DEEP_DIVE", "llm", "llm_response");
        }
        if (cleaned.contains("QA")) return new TaskFamilyDecision("QA", "llm", "llm_response");
        return new TaskFamilyDecision(ruleDecision.taskFamily(), "rule_fallback", "llm_unrecognized -> " + ruleDecision.reason());
    }

    private RuleDecision ruleTaskFamilyDecision(String query, IntentRuleSet ruleSet) {
        String lower = query.toLowerCase(Locale.ROOT);
        if (ruleSet != null && ruleSet.getTaskFamilyRules() != null) {
            for (IntentRuleSet.TaskFamilyRule rule : ruleSet.getTaskFamilyRules()) {
                if (rule == null || rule.getTaskFamily() == null) {
                    continue;
                }
                if (rule.getKeywords() == null || rule.getKeywords().isEmpty()) {
                    continue;
                }
                for (String keyword : rule.getKeywords()) {
                    if (keyword == null || keyword.isBlank()) {
                        continue;
                    }
                    if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                        String reason = rule.getReason() != null ? rule.getReason() : "matches keyword: " + keyword;
                        return new RuleDecision(rule.getTaskFamily(), reason);
                    }
                }
            }
        }

        if (lower.contains("时间线") || lower.contains("timeline")) {
            return new RuleDecision("TIMELINE", "contains 时间线/timeline");
        }
        if (lower.contains("对比") || lower.contains("比较") || lower.contains("compare")) {
            return new RuleDecision("COMPARE", "contains 对比/比较/compare");
        }
        if (lower.contains("总结") || lower.contains("概括") || lower.contains("summary")) {
            return new RuleDecision("SUMMARY", "contains 总结/概括/summary");
        }
        if (lower.contains("深度") || lower.contains("分析") || lower.contains("deep")) {
            return new RuleDecision("DEEP_DIVE", "contains 深度/分析/deep");
        }
        return new RuleDecision("QA", "default");
    }

    private String extractRetrievalMode(String response) {
        if (response == null) {
            return "HYBRID";
        }
        String cleaned = response.toUpperCase();
        if (cleaned.contains("SEMANTIC")) return "SEMANTIC";
        if (cleaned.contains("KEYWORD")) return "KEYWORD";
        if (cleaned.contains("HYBRID")) return "HYBRID";
        return "HYBRID";
    }

    private String extractRiskLevel(String response) {
        if (response == null) {
            return "LOW";
        }
        String cleaned = response.toUpperCase();
        if (cleaned.contains("HIGH")) return "HIGH";
        if (cleaned.contains("MEDIUM")) return "MEDIUM";
        if (cleaned.contains("LOW")) return "LOW";
        return "LOW";
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

    private boolean isDirectAnswerQuery(String query, IntentRuleSet ruleSet) {
        if (query == null) {
            return false;
        }
        String trimmed = query.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (containsNewsKeyword(lower, ruleSet)) {
            return false;
        }
        if (ruleSet != null) {
            if (matchesKeywordRules(lower, ruleSet.getDirectAnswerKeywords())) {
                return true;
            }
            if (matchesPatternRules(trimmed, ruleSet.getDirectAnswerPatterns())) {
                return true;
            }
        }
        if (trimmed.length() <= 20 && trimmed.matches("[0-9\\s+\\-*/.=]+")) {
            return true;
        }
        return trimmed.matches("\\s*\\d+(\\.\\d+)?\\s*[+\\-*/]\\s*\\d+(\\.\\d+)?\\s*=?\\s*");
    }

    private boolean containsNewsKeyword(String lower, IntentRuleSet ruleSet) {
        if (lower == null || lower.isBlank()) {
            return false;
        }
        if (ruleSet != null && matchesKeywordRules(lower, ruleSet.getNewsKeywords())) {
            return true;
        }
        String[] keywords = {
                "新闻",
                "资讯",
                "报道",
                "最新",
                "近期",
                "热搜",
                "headline",
                "news"
        };
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesKeywordRules(String lower, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesPatternRules(String text, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            try {
                if (text.matches(pattern)) {
                    return true;
                }
            } catch (Exception ignored) {
                // ignore invalid patterns
            }
        }
        return false;
    }

    private record RuleDecision(String taskFamily, String reason) {}

    private record TaskFamilyDecision(String taskFamily, String source, String reason) {}
}
