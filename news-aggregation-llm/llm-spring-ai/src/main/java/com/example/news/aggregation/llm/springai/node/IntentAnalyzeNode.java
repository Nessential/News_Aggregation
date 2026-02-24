package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.state.RouterState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 意图分析节点
 * 识别任务族、检索模式与风险等级
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentAnalyzeNode {

    /** ChatClient构建器 */
    private final ChatClient.Builder chatClientBuilder;

    /**
     * 执行意图分析
     *
     * @param state Router状态
     * @return 更新后的状态
     */
    public RouterState execute(RouterState state) {
        state.incrementStep();

        String query = state.getQuery() == null ? "" : state.getQuery();

        try {
            ChatClient client = chatClientBuilder.build();
            String prompt = String.format(
                    "请分析用户查询并输出JSON：\n" +
                    "{\"taskFamily\":\"QA|SUMMARY|COMPARE|TIMELINE|DEEP_DIVE\",\"retrievalMode\":\"SEMANTIC|KEYWORD|HYBRID\",\"riskLevel\":\"LOW|MEDIUM|HIGH\"}\n\n" +
                    "用户查询：%s\n" +
                    "仅输出JSON。",
                    query
            );

            String response = client.prompt()
                    .user(prompt)
                    .call()
                    .content();

            state.setTaskFamily(extractTaskFamily(response, query));
            state.setRetrievalMode(extractRetrievalMode(response));
            state.setRiskLevel(extractRiskLevel(response));
        } catch (Exception e) {
            log.warn("IntentAnalyzeNode failed, fallback to rule-based. error={}", e.getMessage());
            // 简单规则兜底
            state.setTaskFamily(ruleTaskFamily(query));
            state.setRetrievalMode("HYBRID");
            state.setRiskLevel("LOW");
        }

        return state;
    }

    private String ruleTaskFamily(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        if (lower.contains("时间线") || lower.contains("timeline")) {
            return "TIMELINE";
        }
        if (lower.contains("对比") || lower.contains("比较") || lower.contains("compare")) {
            return "COMPARE";
        }
        if (lower.contains("总结") || lower.contains("概括") || lower.contains("summary")) {
            return "SUMMARY";
        }
        if (lower.contains("深度") || lower.contains("分析") || lower.contains("deep")) {
            return "DEEP_DIVE";
        }
        return "QA";
    }

    private String extractTaskFamily(String response, String query) {
        if (response == null) {
            return ruleTaskFamily(query);
        }
        String cleaned = response.toUpperCase().replaceAll("[^A-Z_]", "");
        if (cleaned.contains("SUMMARY")) return "SUMMARY";
        if (cleaned.contains("COMPARE")) return "COMPARE";
        if (cleaned.contains("TIMELINE")) return "TIMELINE";
        if (cleaned.contains("DEEPDIVE") || cleaned.contains("DEEP_DIVE")) return "DEEP_DIVE";
        if (cleaned.contains("QA")) return "QA";
        return ruleTaskFamily(query);
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
}