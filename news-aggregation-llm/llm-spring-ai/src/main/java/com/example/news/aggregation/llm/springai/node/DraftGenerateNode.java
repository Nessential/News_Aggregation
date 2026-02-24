package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.contract.GeneratorDraft;
import com.example.news.aggregation.llm.springai.state.GeneratorState;
import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
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

        try {
            String context = buildContext(evidence);
            String prompt = buildTaskPrompt(query, context, taskFamily);

            ChatClient client = chatClientBuilder.build();
            String answer = client.prompt()
                    .user(prompt)
                    .call()
                    .content();

            GeneratorDraft draft = GeneratorDraft.builder()
                    .answer(answer == null ? "" : answer)
                    .build();

            state.setDraft(draft);
        } catch (Exception e) {
            log.warn("DraftGenerateNode failed, fallback to empty answer. error={}", e.getMessage());
            state.setDraft(GeneratorDraft.builder().answer("").build());
        }

        return state;
    }

    private String buildContext(List<RetrievalResult> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "无可用证据。";
        }
        return evidence.stream()
                .map(r -> String.format("[%s] %s: %s", r.getId(), r.getTitle(), r.getContent()))
                .collect(Collectors.joining("\n\n"));
    }

    private String buildTaskPrompt(String query, String context, String taskFamily) {
        String safeQuery = query == null ? "" : query;
        switch (taskFamily) {
            case "SUMMARY":
                return String.format(
                        "你是新闻摘要助手。请根据证据进行总结。\n\n证据:\n%s\n\n用户需求:%s\n\n要求: 1)覆盖要点 2)避免重复 3)引用[来源ID]\n\n摘要:",
                        context, safeQuery
                );
            case "COMPARE":
                return String.format(
                        "你是新闻对比分析助手。请根据证据进行对比。\n\n证据:\n%s\n\n用户需求:%s\n\n要求: 1)列出异同 2)保持客观 3)引用[来源ID]\n\n对比结果:",
                        context, safeQuery
                );
            case "TIMELINE":
                return String.format(
                        "你是新闻时间线助手。请根据证据生成时间线。\n\n证据:\n%s\n\n用户需求:%s\n\n要求: 1)按时间排序 2)标注日期 3)引用[来源ID]\n\n时间线:",
                        context, safeQuery
                );
            case "DEEP_DIVE":
                return String.format(
                        "你是新闻深度分析助手。请进行深入分析。\n\n证据:\n%s\n\n用户需求:%s\n\n要求: 1)深入分析 2)给出背景与影响 3)引用[来源ID]\n\n分析:",
                        context, safeQuery
                );
            case "QA":
            default:
                return String.format(
                        "你是新闻问答助手。请根据证据回答问题。\n\n证据:\n%s\n\n问题:%s\n\n要求: 1)直接回答 2)引用[来源ID]\n\n答案:",
                        context, safeQuery
                );
        }
    }
}