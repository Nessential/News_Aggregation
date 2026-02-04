package com.example.news.aggregation.agent.pipeline;

import com.example.news.aggregation.agent.client.RetrievalClient;
import com.example.news.aggregation.agent.domain.PipelineContext;
import com.example.news.aggregation.agent.domain.PipelineResult;
import com.example.news.aggregation.agent.tool.RerankTool;
import com.example.news.aggregation.agent.tool.dto.RetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Search Pipeline - 搜索链路
 * 执行流程：向量+关键词检索 -> RRF 融合 -> 去重 -> 返回候选列表
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchPipeline extends BasePipeline {

    private final RetrievalClient retrievalClient;
    private final RerankTool rerankTool;

    @Value("${app.agent.pipeline.max-candidates:50}")
    private int maxCandidates;

    @Override
    public PipelineResult execute(PipelineContext context) {
        logStart(context);
        long startTime = System.currentTimeMillis();

        try {
            String query = context.getQuery();

            // 1. 并行检索（向量 + 关键词）
            log.debug("Step 1: Parallel retrieval (Vector + Keyword)");
            List<RetrievalResult> vectorResults = retrievalClient.vectorSearch(
                    query,
                    maxCandidates / 2,
                    0.5  // 降低阈值，获取更多候选
            );

            List<RetrievalResult> keywordResults = retrievalClient.keywordSearch(
                    query,
                    maxCandidates / 2
            );

            // 2. RRF 融合
            log.debug("Step 2: RRF fusion");
            List<List<RetrievalResult>> resultLists = List.of(vectorResults, keywordResults);
            List<RetrievalResult> fusedResults = rerankTool.rrfFusion(resultLists);

            // 3. 去重（保留最高得分）
            log.debug("Step 3: Deduplication");
            List<RetrievalResult> dedupResults = deduplicateResults(fusedResults);

            // 4. 限制数量
            List<RetrievalResult> finalResults = dedupResults.stream()
                    .limit(maxCandidates)
                    .collect(Collectors.toList());

            // 5. 提取候选文章 ID
            List<Long> candidateIds = finalResults.stream()
                    .map(RetrievalResult::getArticleId)
                    .collect(Collectors.toList());

            // 6. 构建简单摘要
            String answer = buildSearchSummary(query, finalResults.size());

            long executionTime = System.currentTimeMillis() - startTime;
            PipelineResult result = PipelineResult.builder()
                    .answer(answer)
                    .candidateIds(candidateIds)
                    .executionTimeMs(executionTime)
                    .llmCallCount(0)  // Search Pipeline 不使用 LLM
                    .success(true)
                    .build();

            logCompletion(context, result);
            return result;

        } catch (Exception e) {
            log.error("Search Pipeline execution failed", e);
            long executionTime = System.currentTimeMillis() - startTime;
            return PipelineResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .executionTimeMs(executionTime)
                    .llmCallCount(0)
                    .build();
        }
    }

    @Override
    public String getName() {
        return "Search";
    }

    @Override
    public String getDescription() {
        return "Article search pipeline with hybrid retrieval and RRF fusion";
    }

    /**
     * 去重处理
     */
    private List<RetrievalResult> deduplicateResults(List<RetrievalResult> results) {
        return results.stream()
                .collect(Collectors.toMap(
                        RetrievalResult::getArticleId,
                        result -> result,
                        (existing, replacement) ->
                                existing.getScore() >= replacement.getScore() ? existing : replacement
                ))
                .values()
                .stream()
                .sorted((r1, r2) -> Double.compare(r2.getScore(), r1.getScore()))
                .collect(Collectors.toList());
    }

    /**
     * 构建搜索摘要
     */
    private String buildSearchSummary(String query, int resultCount) {
        return String.format("Found %d articles related to: \"%s\"", resultCount, query);
    }
}
