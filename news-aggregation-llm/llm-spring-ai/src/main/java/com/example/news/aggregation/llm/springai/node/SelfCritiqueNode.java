package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.contract.GeneratorDraft;
import com.example.news.aggregation.llm.springai.state.GeneratorState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class SelfCritiqueNode {

    public GeneratorState execute(GeneratorState state) {
        state.incrementStep();

        GeneratorDraft draft = state.getDraft();
        if (draft == null || draft.getAnswerItems() == null || draft.getAnswerItems().isEmpty()) {
            state.setQualityScore(0.0);
            return state;
        }

        List<GeneratorDraft.AnswerItem> items = draft.getAnswerItems();
        int nonBlankTextCount = 0;
        int linkedNewsCount = 0;
        int totalTextLength = 0;

        for (GeneratorDraft.AnswerItem item : items) {
            if (item == null) {
                continue;
            }
            String text = item.getText();
            if (text != null && !text.isBlank()) {
                nonBlankTextCount++;
                totalTextLength += text.length();
            }
            if (item.getNewsIds() != null) {
                linkedNewsCount += (int) item.getNewsIds().stream().filter(id -> id != null && !id.isBlank()).count();
            }
        }

        double score = 0.0;
        if (nonBlankTextCount > 0) {
            score += 0.4;
        }
        if (totalTextLength >= 40) {
            score += 0.2;
        }
        if (linkedNewsCount > 0) {
            score += 0.4;
        }

        state.setQualityScore(Math.min(1.0, score));
        return state;
    }
}
