package com.example.news.aggregation.llm.springai.tool;

import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 重排工具（MMR）。
 */
@Slf4j
@Component
public class RerankTool {

    @Value("${app.llm.mcp.tool.rerank.default-top-n:5}")
    private int defaultTopN;

    @Value("${app.llm.mcp.tool.rerank.diversity-weight:0.3}")
    private double diversityWeight;

    /**
     * MMR 重排，平衡相关性与多样性。
     */
    @Tool(name = "rerank_results", description = "基于 MMR 的结果重排")
    public List<RetrievalResult> mmrRerank(List<RetrievalResult> results, int topN, double lambda) {
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }

        log.info("[工具][rerank_results] 开始执行|inputSize={} |topN={} |lambda={}", results.size(), topN, lambda);

        List<RetrievalResult> selected = new ArrayList<>();
        List<RetrievalResult> candidates = new ArrayList<>(results);

        candidates.sort(Comparator.comparingDouble(RetrievalResult::getScore).reversed());
        selected.add(candidates.remove(0));

        while (selected.size() < topN && !candidates.isEmpty()) {
            double maxMmrScore = Double.NEGATIVE_INFINITY;
            int maxMmrIndex = -1;

            for (int i = 0; i < candidates.size(); i++) {
                RetrievalResult candidate = candidates.get(i);
                double relevanceScore = candidate.getScore();

                double maxSimilarity = 0.0;
                for (RetrievalResult selectedDoc : selected) {
                    double similarity = calculateSimilarity(candidate, selectedDoc);
                    maxSimilarity = Math.max(maxSimilarity, similarity);
                }

                double mmrScore = lambda * relevanceScore - (1 - lambda) * maxSimilarity;
                if (mmrScore > maxMmrScore) {
                    maxMmrScore = mmrScore;
                    maxMmrIndex = i;
                }
            }

            if (maxMmrIndex >= 0) {
                selected.add(candidates.remove(maxMmrIndex));
            } else {
                break;
            }
        }

        log.info("[工具][rerank_results] 执行完成|outputSize={}", selected.size());
        return selected;
    }

    public List<RetrievalResult> mmrRerank(List<RetrievalResult> results, int topN) {
        double lambda = 1.0 - diversityWeight;
        return mmrRerank(results, topN, lambda);
    }

    public List<RetrievalResult> mmrRerank(List<RetrievalResult> results) {
        return mmrRerank(results, defaultTopN);
    }

    private double calculateSimilarity(RetrievalResult doc1, RetrievalResult doc2) {
        double similarity = 0.0;

        String id1Prefix = doc1.getId().split("_")[0];
        String id2Prefix = doc2.getId().split("_")[0];
        if (id1Prefix.equals(id2Prefix)) {
            similarity += 0.3;
        }

        int len1 = doc1.getTitle().length();
        int len2 = doc2.getTitle().length();
        double lengthRatio = Math.min(len1, len2) / (double) Math.max(len1, len2);
        similarity += lengthRatio * 0.4;

        if (doc1.getSource() != null && doc1.getSource().equals(doc2.getSource())) {
            similarity += 0.3;
        }

        return Math.min(similarity, 1.0);
    }

    public List<RetrievalResult> llmRerank(String query, List<RetrievalResult> results, int topN) {
        log.info("[工具][rerank_results] LLM 重排暂未启用，回退 MMR|query={}", query);
        return mmrRerank(results, topN);
    }
}