package com.example.news.aggregation.agent.tool;

import com.example.news.aggregation.agent.tool.dto.RetrievalResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 重排序工具。
 * 实现 RRF 融合与 MMR 多样性重排序。
 */
@Slf4j
@Component
public class RerankTool implements Tool<RerankTool.RerankInput, List<RetrievalResult>> {

    private static final int RRF_K = 60; // RRF 常数
    private static final double MMR_LAMBDA = 0.7; // MMR 权重系数

    @Override
    public List<RetrievalResult> execute(RerankInput input) {
        switch (input.mode) {
            case RRF -> {
                return rrfFusion(input.resultLists);
            }
            case MMR -> {
                return mmrRerank(input.results, input.topK, MMR_LAMBDA);
            }
            default -> {
                log.warn("Unknown rerank mode: {}, returning first list", input.mode);
                return input.resultLists.isEmpty() ? Collections.emptyList() : input.resultLists.get(0);
            }
        }
    }

    @Override
    public String getName() {
        return "rerank";
    }

    @Override
    public String getDescription() {
        return "Rerank and fuse retrieval results using RRF or MMR";
    }

    /**
     * RRF (Reciprocal Rank Fusion) 融合。
     * 将多个检索结果列表融合为一个排序列表。
     */
    public List<RetrievalResult> rrfFusion(List<List<RetrievalResult>> resultLists) {
        if (resultLists == null || resultLists.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, Double> rrfScores = new HashMap<>();
        Map<Long, RetrievalResult> resultMap = new HashMap<>();

        // 计算每个文档的 RRF 得分

        for (List<RetrievalResult> results : resultLists) {
            for (int rank = 0; rank < results.size(); rank++) {
                RetrievalResult result = results.get(rank);
                Long articleId = result.getArticleId();

                double rrfScore = 1.0 / (RRF_K + rank + 1);
                rrfScores.merge(articleId, rrfScore, Double::sum);
                resultMap.putIfAbsent(articleId, result);
            }
        }

        // 按 RRF 得分排序
        List<RetrievalResult> fusedResults = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(entry -> {
                    RetrievalResult result = resultMap.get(entry.getKey());
                    result.setScore(entry.getValue());
                    return result;
                })
                .collect(Collectors.toList());

        log.info("RRF fusion completed: {} lists -> {} unique articles", resultLists.size(), fusedResults.size());
        return fusedResults;
    }

    /**
     * MMR (Maximal Marginal Relevance) 重排序。
     * 平衡相关性和多样性。
     */
    public List<RetrievalResult> mmrRerank(List<RetrievalResult> results, int topK, double lambda) {
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }

        if (results.size() <= topK) {
            return new ArrayList<>(results);
        }

        List<RetrievalResult> selected = new ArrayList<>();
        Set<Long> selectedIds = new HashSet<>();
        List<RetrievalResult> candidates = new ArrayList<>(results);

        // 选择第一个（最高相关性）
        RetrievalResult first = candidates.remove(0);
        selected.add(first);
        selectedIds.add(first.getArticleId());

        // 迭代选择剩余文档

        while (selected.size() < topK && !candidates.isEmpty()) {
            int bestIndex = -1;
            double bestScore = Double.NEGATIVE_INFINITY;

            for (int i = 0; i < candidates.size(); i++) {
                RetrievalResult candidate = candidates.get(i);

                // 相关性得分（已归一）
                double relevance = candidate.getScore();

                // 多样性得分（与已选文档的最大相似度）
                double maxSimilarity = calculateMaxSimilarity(candidate, selected);

                // MMR 得分
                double mmrScore = lambda * relevance - (1 - lambda) * maxSimilarity;

                if (mmrScore > bestScore) {
                    bestScore = mmrScore;
                    bestIndex = i;
                }
            }

            if (bestIndex >= 0) {
                RetrievalResult bestCandidate = candidates.remove(bestIndex);
                selected.add(bestCandidate);
                selectedIds.add(bestCandidate.getArticleId());
            } else {
                break;
            }
        }

        log.info("MMR rerank completed: {} -> {} articles (lambda={})", results.size(), selected.size(), lambda);
        return selected;
    }

    /**
     * 计算候选文档与已选文档的最大相似度
     * （简化实现，基于 ID 距离）
     */
    private double calculateMaxSimilarity(RetrievalResult candidate, List<RetrievalResult> selected) {
        if (selected.isEmpty()) {
            return 0.0;
        }

        // 简化实现：基于文章 ID 差异计算相似度
        // 实际应用中应使用 embedding 向量的余弦相似度
        double maxSim = 0.0;
        for (RetrievalResult selectedResult : selected) {
            long idDiff = Math.abs(candidate.getArticleId() - selectedResult.getArticleId());
            double similarity = 1.0 / (1.0 + idDiff);
            maxSim = Math.max(maxSim, similarity);
        }
        return maxSim;
    }

    /**
     * 重排序输入参数。
     */
    public record RerankInput(
            RerankMode mode,
            List<List<RetrievalResult>> resultLists,  // RRF 模式使用
            List<RetrievalResult> results,            // MMR 模式使用
            int topK
    ) {
        public static RerankInput forRRF(List<List<RetrievalResult>> resultLists) {
            return new RerankInput(RerankMode.RRF, resultLists, null, 0);
        }

        public static RerankInput forMMR(List<RetrievalResult> results, int topK) {
            return new RerankInput(RerankMode.MMR, null, results, topK);
        }
    }

    public enum RerankMode {
        RRF,  // Reciprocal Rank Fusion
        MMR   // Maximal Marginal Relevance
    }
}