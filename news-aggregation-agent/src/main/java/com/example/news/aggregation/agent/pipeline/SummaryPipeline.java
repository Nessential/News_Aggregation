package com.example.news.aggregation.agent.pipeline;

import com.example.news.aggregation.agent.client.LLMClient;
import com.example.news.aggregation.agent.client.RetrievalClient;
import com.example.news.aggregation.agent.domain.PipelineContext;
import com.example.news.aggregation.agent.domain.PipelineResult;
import com.example.news.aggregation.agent.tool.dto.RetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Summary Pipeline - 总结链路
 * 执行流程：检索 -> 排序 TopK -> LLM 总结
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryPipeline extends BasePipeline {

    private final RetrievalClient retrievalClient;
    private final LLMClient llmClient;

    @Value("${app.agent.pipeline.default-top-k:10}")
    private int defaultTopK;

    @Value("${app.agent.pipeline.max-candidates:50}")
    private int maxCandidates;

    @Override
    public PipelineResult execute(PipelineContext context) {
        logStart(context);
        long startTime = System.currentTimeMillis();
        int llmCallCount = 0;

        try {
            String query = context.getQuery();

            // 1. 检索相关文档
            log.debug("Step 1: Retrieving articles for summary");
            List<RetrievalResult> retrievedResults = retrievalClient.hybridSearch(
                    query,
                    Math.min(maxCandidates, 30),
                    0.6  // 降低阈值，获取更多候选
            );

            // 2. 按得分排序，取 TopK
            List<RetrievalResult> topResults = retrievedResults.stream()
                    .sorted((r1, r2) -> Double.compare(r2.getScore(), r1.getScore()))
                    .limit(defaultTopK)
                    .collect(Collectors.toList());

            // 3. 提取候选文章 ID
            List<Long> candidateIds = topResults.stream()
                    .map(RetrievalResult::getArticleId)
                    .collect(Collectors.toList());

            // 4. 构建证据文本
            String evidence = buildEvidence(topResults);

            // 5. LLM 生成总结
            log.debug("Step 2: Generating summary with LLM");
            String prompt = buildSummaryPrompt(query, evidence, context);
            String answer = llmClient.generate(prompt);
            llmCallCount++;

            // 6. 提取引用
            List<String> citations = extractCitations(topResults);

            long executionTime = System.currentTimeMillis() - startTime;
            PipelineResult result = PipelineResult.builder()
                    .answer(answer)
                    .candidateIds(candidateIds)
                    .citations(citations)
                    .executionTimeMs(executionTime)
                    .llmCallCount(llmCallCount)
                    .success(true)
                    .build();

            logCompletion(context, result);
            return result;

        } catch (Exception e) {
            log.error("Summary Pipeline execution failed", e);
            long executionTime = System.currentTimeMillis() - startTime;
            return PipelineResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .executionTimeMs(executionTime)
                    .llmCallCount(llmCallCount)
                    .build();
        }
    }

    @Override
    public String getName() {
        return "Summary";
    }

    @Override
    public String getDescription() {
        return "Multi-document summarization pipeline";
    }

    /**
     * 构建证据文本
     */
    private String buildEvidence(List<RetrievalResult> results) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            RetrievalResult result = results.get(i);
            sb.append(String.format("--- Article %d (ID: %d, Score: %.2f) ---\n",
                    i + 1, result.getArticleId(), result.getScore()));
            sb.append(result.getMatchedSnippet()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 构建 Summary Prompt
     */
    private String buildSummaryPrompt(String query, String evidence, PipelineContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a professional news summarizer. Create a comprehensive summary based on the provided articles.\n\n");
        prompt.append("Topic: ").append(query).append("\n\n");
        prompt.append("Articles:\n").append(evidence).append("\n");
        prompt.append("Instructions:\n");
        prompt.append("1. Synthesize information from all articles into a coherent summary\n");
        prompt.append("2. Identify key themes, trends, and important developments\n");
        prompt.append("3. Maintain factual accuracy and cite article IDs when appropriate\n");
        prompt.append("4. Organize the summary logically with clear structure\n");
        prompt.append("5. Keep the summary concise but comprehensive (300-500 words)\n\n");
        prompt.append("Summary:");
        return prompt.toString();
    }

    /**
     * 提取引用信息
     */
    private List<String> extractCitations(List<RetrievalResult> results) {
        return results.stream()
                .limit(10)
                .map(result -> String.format("Article %d", result.getArticleId()))
                .collect(Collectors.toList());
    }
}
