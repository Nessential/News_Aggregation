package com.example.news.aggregation.llm.springai.tool;

import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM重排序工具 (使用MMR算法)
 * 
 * MVP实现: 纯MMR算法，未使用真实LLM
 * 生产环境: 可集成DashScope Rerank API或使用LLM打分
 */
@Slf4j
@Component
public class RerankTool {

    @Value("${app.llm.mcp.tool.rerank.default-top-n:5}")
    private int defaultTopN;

    @Value("${app.llm.mcp.tool.rerank.diversity-weight:0.3}")
    private double diversityWeight;

    /**
     * MMR重排序 (Maximal Marginal Relevance)
     * 平衡相关性和多样性
     *
     * @param results 原始检索结果
     * @param topN    返回结果数量
     * @param lambda  相关性权重 (0.0-1.0, 1.0 = 纯相关性, 0.0 = 纯多样性)
     * @return 重排序后的结果
     */
    public List<RetrievalResult> mmrRerank(List<RetrievalResult> results, int topN, double lambda) {
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }

        log.info("Executing MMR rerank: input_size={}, topN={}, lambda={}", results.size(), topN, lambda);

        // 已选择的结果集
        List<RetrievalResult> selected = new ArrayList<>();
        // 候选结果集（浅拷贝以避免修改原列表）
        List<RetrievalResult> candidates = new ArrayList<>(results);

        // 1. 首先选择相关性最高的文档
        candidates.sort(Comparator.comparingDouble(RetrievalResult::getScore).reversed());
        selected.add(candidates.remove(0));

        // 2. 迭代选择剩余文档
        while (selected.size() < topN && !candidates.isEmpty()) {
            double maxMmrScore = Double.NEGATIVE_INFINITY;
            int maxMmrIndex = -1;

            // 对每个候选文档计算MMR分数
            for (int i = 0; i < candidates.size(); i++) {
                RetrievalResult candidate = candidates.get(i);
                
                // 相关性分数 (已归一化)
                double relevanceScore = candidate.getScore();

                // 计算与已选文档的最大相似度 (这里使用简化的基于ID和内容的相似度)
                double maxSimilarity = 0.0;
                for (RetrievalResult selectedDoc : selected) {
                    double similarity = calculateSimilarity(candidate, selectedDoc);
                    maxSimilarity = Math.max(maxSimilarity, similarity);
                }

                // MMR分数 = λ * 相关性 - (1-λ) * 最大相似度
                double mmrScore = lambda * relevanceScore - (1 - lambda) * maxSimilarity;

                if (mmrScore > maxMmrScore) {
                    maxMmrScore = mmrScore;
                    maxMmrIndex = i;
                }
            }

            // 选择MMR分数最高的文档
            if (maxMmrIndex >= 0) {
                selected.add(candidates.remove(maxMmrIndex));
            } else {
                break; // 无法找到更多候选
            }
        }

        log.info("MMR rerank completed: output_size={}", selected.size());
        return selected;
    }

    /**
     * MMR重排序（使用默认topN和配置的lambda）
     */
    public List<RetrievalResult> mmrRerank(List<RetrievalResult> results, int topN) {
        // lambda = 1 - diversityWeight
        // diversityWeight=0.3 → lambda=0.7 (70%相关性, 30%多样性)
        double lambda = 1.0 - diversityWeight;
        return mmrRerank(results, topN, lambda);
    }

    /**
     * MMR重排序（使用所有默认值）
     */
    public List<RetrievalResult> mmrRerank(List<RetrievalResult> results) {
        return mmrRerank(results, defaultTopN);
    }

    /**
     * 计算两个文档的相似度 (简化实现)
     * 
     * MVP实现: 基于ID前缀和内容长度的简单相似度
     * 生产环境: 应使用向量cosine相似度或LLM判断
     *
     * @return 相似度分数 (0.0-1.0)
     */
    private double calculateSimilarity(RetrievalResult doc1, RetrievalResult doc2) {
        // 如果ID前缀相同（来自同一数据源），相似度+0.3
        double similarity = 0.0;
        
        String id1Prefix = doc1.getId().split("_")[0];
        String id2Prefix = doc2.getId().split("_")[0];
        
        if (id1Prefix.equals(id2Prefix)) {
            similarity += 0.3;
        }
        
        // 如果标题相似（简单长度比较），相似度+0.4
        int len1 = doc1.getTitle().length();
        int len2 = doc2.getTitle().length();
        double lengthRatio = Math.min(len1, len2) / (double) Math.max(len1, len2);
        similarity += lengthRatio * 0.4;
        
        // 如果来源相同，相似度+0.3
        if (doc1.getSource() != null && doc1.getSource().equals(doc2.getSource())) {
            similarity += 0.3;
        }
        
        return Math.min(similarity, 1.0);
    }

    /**
     * LLM重排序 (使用真实LLM打分)
     * 
     * MVP: 未实现，fallback到MMR
     * 生产环境: 调用DashScope或其他LLM对文档相关性打分
     */
    public List<RetrievalResult> llmRerank(String query, List<RetrievalResult> results, int topN) {
        log.info("LLM rerank not implemented in MVP, falling back to MMR");
        return mmrRerank(results, topN);
    }
}
