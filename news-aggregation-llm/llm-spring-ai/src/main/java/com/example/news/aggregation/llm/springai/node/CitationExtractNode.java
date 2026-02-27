package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.contract.GeneratorDraft;
import com.example.news.aggregation.llm.springai.state.GeneratorState;
import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用提取节点
 * 解析答案中的[来源ID]并构建引用列表
 */
@Slf4j
@Component
public class CitationExtractNode {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[([^\\]]+)\\]");

    /**
     * 执行引用提取
     *
     * @param state Generator状态
     * @return 更新后的状态
     */
    public GeneratorState execute(GeneratorState state) {
        state.incrementStep();

        GeneratorDraft draft = state.getDraft();
        if (draft == null || draft.getAnswer() == null) {
            return state;
        }
        if (draft.getCitations() != null && !draft.getCitations().isEmpty()) {
            return state;
        }

        List<GeneratorDraft.Citation> citations = extractCitations(draft.getAnswer(), state.getEvidence());
        draft.setCitations(citations);
        state.setDraft(draft);
        return state;
    }

    private List<GeneratorDraft.Citation> extractCitations(String answer, List<RetrievalResult> evidence) {
        List<GeneratorDraft.Citation> citations = new ArrayList<>();
        if (answer == null || evidence == null || evidence.isEmpty()) {
            return citations;
        }

        Map<String, RetrievalResult> evidenceMap = new HashMap<>();
        for (RetrievalResult result : evidence) {
            String evidenceId = normalizeSourceId(result.getId());
            if (evidenceId != null && !evidenceId.isBlank()) {
                evidenceMap.put(evidenceId, result);
            }
        }

        Matcher matcher = CITATION_PATTERN.matcher(answer);
        int position = 0;
        while (matcher.find()) {
            String sourceId = normalizeSourceId(matcher.group(1));
            if (!evidenceMap.containsKey(sourceId)) {
                continue;
            }

            int start = Math.max(0, matcher.start() - 50);
            int end = Math.min(answer.length(), matcher.end() + 50);
            String context = answer.substring(start, end).trim();

            citations.add(GeneratorDraft.Citation.builder()
                    .sourceId(sourceId)
                    .text(context)
                    .position(position++)
                    .build());
        }

        log.debug("Extracted {} citations", citations.size());
        return citations;
    }

    private String normalizeSourceId(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }
}
