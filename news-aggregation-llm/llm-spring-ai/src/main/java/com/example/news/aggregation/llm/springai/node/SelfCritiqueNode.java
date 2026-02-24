package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.state.GeneratorState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自我评审节点
 * 使用启发式规则对答案进行质量评分
 */
@Slf4j
@Component
public class SelfCritiqueNode {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[[^\\]]+\\]");

    /**
     * 执行质量评分
     *
     * @param state Generator状态
     * @return 更新后的状态
     */
    public GeneratorState execute(GeneratorState state) {
        state.incrementStep();

        if (state.getDraft() == null || state.getDraft().getAnswer() == null) {
            state.setQualityScore(0.0);
            return state;
        }

        String answer = state.getDraft().getAnswer();
        double score = 0.0;

        // 1) 长度评分
        int length = answer.length();
        if (length >= 50 && length <= 2000) {
            score += 0.4;
        } else if (length >= 20) {
            score += 0.2;
        }

        // 2) 引用数量评分
        int citationCount = countCitations(answer);
        if (citationCount >= 2 && citationCount <= 5) {
            score += 0.4;
        } else if (citationCount == 1) {
            score += 0.2;
        }

        // 3) 基础可用性评分
        if (!answer.isBlank()) {
            score += 0.2;
        }

        score = Math.min(1.0, score);
        state.setQualityScore(score);
        return state;
    }

    private int countCitations(String answer) {
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
