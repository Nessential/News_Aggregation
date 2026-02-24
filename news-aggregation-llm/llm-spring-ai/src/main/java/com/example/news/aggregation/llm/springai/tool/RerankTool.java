package com.example.news.aggregation.llm.springai.tool;

import com.alibaba.cloud.ai.mcp.McpTool;
import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 重排序工具（使用MMR算法）
 * MVP阶段采用启发式MMR，后续可接入LLM重排服务。
 */
@Slf4j
@Component
public class RerankTool {

    @Value("${app.llm.mcp.tool.rerank.default-top-n:5}")
    private int defaultTopN;

    @Value("${app.llm.mcp.tool.rerank.diversity-weight:0.3}")
    private double diversityWeight;

    /**
     * MMR重排序（Maximal Marginal Relevance）
     * 平衡相关性与多样性。
     *
     * @param results 原始检索结果
     * @param topN    返回结果数量
     * @param lambda  相关性权重(0.0-1.0, 1.0=纯相关, 0.0=纯多样)
     * @return 重排序后的结果
     */
    @McpTool(name = "rerank_results", description = "基于MMR的结果重排")
    public List<RetrievalResult> mmrRerank(List<RetrievalResult> results, int topN, double lambda) {
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }

        log.info("Executing MMR rerank: input_size={}, topN={}, lambda={}", results.size(), topN, lambda);

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

        log.info("MMR rerank completed: output_size={}", selected.size());
        return selected;
    }

    /**
     * MMR重排序（使用默认topN与配置的lambda）
     */
    public List<RetrievalResult> mmrRerank(List<RetrievalResult> results, int topN) {
        double lambda = 1.0 - diversityWeight;
        return mmrRerank(results, topN, lambda);
    }

    /**
     * MMR重排序（使用默认参数）
     */
    public List<RetrievalResult> mmrRerank(List<RetrievalResult> results) {
        return mmrRerank(results, defaultTopN);
    }

    /**
     * 计算两个文档的相似度（简化实现）
     *
     * @return 相似度分数(0.0-1.0)
     */
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

    /**
     * LLM重排序（后续可接入真实LLM）
     */
    public List<RetrievalResult> llmRerank(String query, List<RetrievalResult> results, int topN) {
        log.info("LLM rerank not implemented in MVP, falling back to MMR");
        return mmrRerank(results, topN);
    }
}