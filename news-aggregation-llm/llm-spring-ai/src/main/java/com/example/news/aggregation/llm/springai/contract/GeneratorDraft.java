package com.example.news.aggregation.llm.springai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratorDraft {

    /** Structured answer items; each item links to one or more news IDs. */
    private List<AnswerItem> answerItems;

    /** Quality score in range [0,1]. */
    private Double qualityScore;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnswerItem {
        /** One answer sentence/paragraph shown to user. */
        private String text;

        /** Related news ids backing this answer item. */
        private List<String> newsIds;
    }

    public static GeneratorDraft conservative(String evidenceSummary) {
        AnswerItem item = AnswerItem.builder()
                .text("根据可用信息，" + evidenceSummary)
                .newsIds(new ArrayList<>())
                .build();
        return GeneratorDraft.builder()
                .answerItems(List.of(item))
                .qualityScore(0.5)
                .build();
    }
}
