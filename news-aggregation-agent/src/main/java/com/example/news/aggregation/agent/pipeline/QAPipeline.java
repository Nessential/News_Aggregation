package com.example.news.aggregation.agent.pipeline;

import com.example.news.aggregation.agent.client.LLMClient;
import com.example.news.aggregation.agent.client.RetrievalClient;
import com.example.news.aggregation.agent.domain.PipelineContext;
import com.example.news.aggregation.agent.domain.PipelineResult;
import com.example.news.aggregation.agent.tool.RerankTool;
import com.example.news.aggregation.agent.tool.dto.RetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * QA Pipeline - 问答链路
 * 执行流程：向量/关键词检索 -> RRF 融合 -> MMR 重排 -> LLM 生成
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QAPipeline extends BasePipeline {

    private final RetrievalClient retrievalClient;
    private final RerankTool rerankTool;
    private final LLMClient llmClient;

    @Value("${app.agent.pipeline.default-top-k:10}")
    private int defaultTopK;

    @Override
    public PipelineResult execute(PipelineContext context) {
        logStart(context);
        long startTime = System.currentTimeMillis();
        int llmCallCount = 0;

        try {
            String query = context.getQuery();

            // 1. 向量 + 关键词检索
            log.debug("Step 1: Hybrid retrieval for query: {}", query);
            List<RetrievalResult> vectorResults = retrievalClient.vectorSearch(query, defaultTopK, 0.7);
            List<RetrievalResult> keywordResults = retrievalClient.keywordSearch(query, defaultTopK);

            // 2. RRF 融合
            log.debug("Step 2: RRF fusion");
            List<List<RetrievalResult>> resultLists = List.of(vectorResults, keywordResults);
            List<RetrievalResult> fusedResults = rerankTool.rrfFusion(resultLists);

            // 3. MMR 重排
            log.debug("Step 3: MMR reranking");
            List<RetrievalResult> rerankedResults = rerankTool.mmrRerank(fusedResults, defaultTopK, 0.7);

            // 4. 提取候选文章 ID
            List<Long> candidateIds = rerankedResults.stream()
                    .map(RetrievalResult::getArticleId)
                    .collect(Collectors.toList());

            // 5. 证据构建
            String evidence = buildEvidence(rerankedResults);

            // 6. LLM 生成
            log.debug("Step 4: Generating answer with LLM");
            String prompt = buildQAPrompt(query, evidence, context);
            String answer = llmClient.generate(prompt);
            llmCallCount++;

            // 7. 提取引用
            List<String> citations = extractCitations(rerankedResults);

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
            log.error("QA Pipeline execution failed", e);
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
        return "QA";
    }

    @Override
    public String getDescription() {
        return "Question answering pipeline with hybrid retrieval and LLM generation";
    }

    /**
     * 构建证据文本
     */
    private String buildEvidence(List<RetrievalResult> results) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            RetrievalResult result = results.get(i);
            sb.append(String.format("[%d] Article ID: %d, Score: %.2f\n",
                    i + 1, result.getArticleId(), result.getScore()));
            sb.append(result.getMatchedSnippet()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 构建 QA Prompt
     */
    private String buildQAPrompt(String query, String evidence, PipelineContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a helpful news assistant. Answer the user's question based on the provided evidence.\n\n");
        prompt.append("Question: ").append(query).append("\n\n");
        prompt.append("Evidence:\n").append(evidence).append("\n");
        prompt.append("Instructions:\n");
        prompt.append("1. Answer the question using ONLY the evidence provided\n");
        prompt.append("2. Cite sources by mentioning article IDs [1], [2], etc.\n");
        prompt.append("3. If the evidence is insufficient, say so honestly\n");
        prompt.append("4. Keep the answer concise and relevant\n\n");
        prompt.append("Answer:");
        return prompt.toString();
    }

    /**
     * 提取引用信息
     */
    private List<String> extractCitations(List<RetrievalResult> results) {
        List<String> citations = new ArrayList<>();
        for (int i = 0; i < Math.min(results.size(), 5); i++) {
            RetrievalResult result = results.get(i);
            citations.add(String.format("[%d] Article %d (score: %.2f)",
                    i + 1, result.getArticleId(), result.getScore()));
        }
        return citations;
    }
}
