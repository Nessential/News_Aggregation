package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.state.RouterState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 指代消解节点
 * 将“它/这件事/最近”等上下文指代转换为明确表达
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReferenceResolveNode {

    /** ChatClient构建器 */
    private final ChatClient.Builder chatClientBuilder;

    /**
     * 执行指代消解
     *
     * @param state Router状态
     * @return 更新后的状态
     */
    public RouterState execute(RouterState state) {
        state.incrementStep();

        String query = state.getQuery();
        List<String> history = state.getHistory();

        if (query == null || query.isBlank()) {
            state.setResolvedQuery(query);
            return state;
        }

        // 无历史时直接返回
        if (history == null || history.isEmpty()) {
            state.setResolvedQuery(query);
            return state;
        }

        try {
            ChatClient client = chatClientBuilder.build();
            String prompt = String.format(
                    "请根据对话历史消解用户查询中的指代，输出消解后的单句查询。\n" +
                    "历史：%s\n" +
                    "查询：%s\n" +
                    "仅输出消解后的查询。",
                    String.join(" | ", history),
                    query
            );

            String resolved = client.prompt()
                    .user(prompt)
                    .call()
                    .content();

            state.setResolvedQuery((resolved == null || resolved.isBlank()) ? query : resolved.trim());
        } catch (Exception e) {
            log.warn("ReferenceResolveNode failed, fallback to original query. error={}", e.getMessage());
            state.setResolvedQuery(query);
        }

        return state;
    }
}