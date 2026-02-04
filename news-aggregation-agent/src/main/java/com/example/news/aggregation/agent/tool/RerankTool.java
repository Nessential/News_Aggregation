package com.example.news.aggregation.agent.tool;

import com.example.news.aggregation.agent.tool.dto.RetrievalResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 重排序工具
 * 实现RRF融合和MMR多样性重排序
 */
@Slf4j
@Component
public class RerankTool implements Tool<RerankTool.RerankInput, List<RetrievalResult>> {
    
    private static final int RRF_K = 60; // RRF常数
    private static final double MMR_LAMBDA = 0.7; // MMR λ参数 (相关性权重)
    
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
     * RRF (Reciprocal Rank Fusion) 融合
     * 将多个检索结果列表融合为一个排序列表
     */
    public List<RetrievalResult> rrfFusion(List<List<RetrievalResult>> resultLists) {
        if (resultLists == null || resultLists.isEmpty()) {
            return Collections.emptyList();
        }
        
        Map<Long, Double> rrfScores = new HashMap<>();
        Map<Long, RetrievalResult> resultMap = new HashMap<>();
        
        // 计算每个文档的RRF得分
        for (List<RetrievalResult> results : resultLists) {
            for (int rank = 0; rank < results.size(); rank++) {
                RetrievalResult result = results.get(rank);
                Long articleId = result.getArticleId();
                
                double rrfScore = 1.0 / (RRF_K + rank + 1);
                rrfScores.merge(articleId, rrfScore, Double::sum);
                resultMap.putIfAbsent(articleId, result);
            }
        }
        
        // 按RRF得分排序
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
     * MMR (Maximal Marginal Relevance) 重排序
     * 平衡相关性和多样性
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
        
        // 选择第一个 (最高相关性)
        RetrievalResult first = candidates.remove(0);
        selected.add(first);
        selectedIds.add(first.getArticleId());
        
        // 迭代选择剩余文档
        while (selected.size() < topK && !candidates.isEmpty()) {
            int bestIndex = -1;
            double bestScore = Double.NEGATIVE_INFINITY;
            
            for (int i = 0; i < candidates.size(); i++) {
                RetrievalResult candidate = candidates.get(i);
                
                // 相关性得分 (归一化)
                double relevance = candidate.getScore();
                
                // 多样性得分 (与已选文档的最大相似度)
                double maxSimilarity = calculateMaxSimilarity(candidate, selected);
                
                // MMR得分
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
     * (简化实现，基于ID距离)
     */
    private double calculateMaxSimilarity(RetrievalResult candidate, List<RetrievalResult> selected) {
        if (selected.isEmpty()) {
            return 0.0;
        }
        
        // 简化实现：基于文章ID差异计算相似度
        // 实际应用中应该使用embedding向量的余弦相似度
        double maxSim = 0.0;
        for (RetrievalResult selectedResult : selected) {
            long idDiff = Math.abs(candidate.getArticleId() - selectedResult.getArticleId());
            double similarity = 1.0 / (1.0 + idDiff);
            maxSim = Math.max(maxSim, similarity);
        }
        return maxSim;
    }
    
    /**
     * 重排序输入参数
     */
    public record RerankInput(
            RerankMode mode,
            List<List<RetrievalResult>> resultLists,  // RRF模式使用
            List<RetrievalResult> results,            // MMR模式使用
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
