package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.state.RouterState;
import com.example.news.aggregation.llm.springai.prompt.PromptRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 指代消解节点。
 * 将“它/这件事/最近”等上下文指代转换为明确表达。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReferenceResolveNode {

    /** ChatClient 构建器 */
    private final ChatClient.Builder chatClientBuilder;
    /** 提示词仓库 */
    private final PromptRegistry promptRegistry;

    /**
     * 执行指代消解。
     *
     * @param state Router 状态
     * @return 更新后的状态
     */
    public RouterState execute(RouterState state) {
        state.incrementStep();

        String query = state.getQuery();
        List<String> history = state.getHistory();
        String sessionId = state.getSessionId() != null ? state.getSessionId() : "unknown";
        // 流程日志：进入指代消解
        log.info("进入指代消解FLOW|router|node=reference_resolve|step=start|sessionId={}|query={}|next=slot_extract_or_completeness_check",
                sessionId, truncate(query, 200));

        if (query == null || query.isBlank()) {
            state.setResolvedQuery(query);
            log.info("指代消解决策-空查询跳过FLOW|router|node=reference_resolve|decision=skip_empty_query|sessionId={}|next=completeness_check",
                    sessionId);
            return state;
        }

        // 无历史时直接返回
        if (history == null || history.isEmpty()) {
            state.setResolvedQuery(query);
            log.info("指代消解决策-无历史跳过FLOW|router|node=reference_resolve|decision=skip_no_history|sessionId={}|next=completeness_check",
                    sessionId);
            return state;
        }

        try {
            ChatClient client = chatClientBuilder.build();
            String template = promptRegistry.getPrompt("reference-resolve", "");
            if (template == null || template.isBlank()) {
                state.setResolvedQuery(query);
                log.warn("ReferenceResolveNode missing prompt template, skip resolution.");
                return state;
            }
            String prompt = renderTemplate(template, String.join(" | ", history), query);

            String resolved = client.prompt()
                    .user(prompt)
                    .call()
                    .content();

            String finalResolved = (resolved == null || resolved.isBlank()) ? query : resolved.trim();
            state.setResolvedQuery(finalResolved);
            log.info("指代消解-模型原始输出(截断)={}", truncate(resolved, 200));
            log.info("指代消解完成FLOW|router|node=reference_resolve|decision=resolved|sessionId={}|resolvedQuery={}|next=slot_extract_or_completeness_check",
                    sessionId, truncate(finalResolved, 200));
        } catch (Exception e) {
            log.warn("ReferenceResolveNode failed, fallback to original query. error={}", e.getMessage());
            state.setResolvedQuery(query);
            log.info("指代消解失败-回退原查询FLOW|router|node=reference_resolve|decision=fallback_original|sessionId={}|next=slot_extract_or_completeness_check",
                    sessionId);
        }

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

    private String renderTemplate(String template, String history, String query) {
        if (template == null) {
            return "";
        }
        String safeHistory = history == null ? "" : history;
        String safeQuery = query == null ? "" : query;
        return template
                .replace("{{history}}", safeHistory)
                .replace("{{query}}", safeQuery);
    }
}
