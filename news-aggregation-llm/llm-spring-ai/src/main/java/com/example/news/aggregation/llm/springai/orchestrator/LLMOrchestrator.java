package com.example.news.aggregation.llm.springai.orchestrator;

import com.example.news.aggregation.llm.springai.config.GraphProperties;
import com.example.news.aggregation.llm.springai.contract.ChatResponse;
import com.example.news.aggregation.llm.springai.contract.ExecutionPlan;
import com.example.news.aggregation.llm.springai.contract.GeneratorDraft;
import com.example.news.aggregation.llm.springai.contract.PlanRequest;
import com.example.news.aggregation.llm.springai.contract.RouterRequest;
import com.example.news.aggregation.llm.springai.contract.RouterResult;
import com.example.news.aggregation.llm.springai.service.GeneratorService;
import com.example.news.aggregation.llm.springai.service.PlannerService;
import com.example.news.aggregation.llm.springai.service.RouterService;
import com.example.news.aggregation.llm.springai.tool.RerankTool;
import com.example.news.aggregation.llm.springai.tool.RetrieveTool;
import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LLMOrchestrator {

    private final RouterService routerService;
    private final PlannerService plannerService;
    private final GeneratorService generatorService;
    private final RetrieveTool retrieveTool;
    private final RerankTool rerankTool;
    private final GraphProperties graphProperties;

    public ChatResponse processQuery(String sessionId, String userMessage, String userId) {
        log.info("[Orchestrator] Processing query: sessionId={}, userId={}, message={}", sessionId, userId, userMessage);

        try {
            RouterResult routerResult = route(sessionId, userMessage);
            if (Boolean.TRUE.equals(routerResult.getNeedsClarification())) {
                return ChatResponse.builder()
                        .sessionId(sessionId)
                        .answer(routerResult.getClarificationQuestion())
                        .taskFamily(routerResult.getTaskFamily())
                        .timestamp(System.currentTimeMillis())
                        .build();
            }

            if (graphProperties.isPlannerEnabled() && requiresPlanner(routerResult.getTaskFamily())) {
                ExecutionPlan plan = plannerService.plan(PlanRequest.builder().query(userMessage).routerResult(routerResult).build());
                log.info("[Orchestrator] Planner generated plan: tasks={}", plan.getSteps() != null ? plan.getSteps().size() : 0);
            }

            String effectiveQuery = routerResult.getQueryInterpretation();
            if (effectiveQuery == null || effectiveQuery.isBlank()) {
                effectiveQuery = userMessage;
            }
            List<RetrievalResult> retrievalResults = retrieve(effectiveQuery, routerResult);
            List<RetrievalResult> rerankedResults = rerank(effectiveQuery, retrievalResults);

            GeneratorDraft draft = generatorService.generate(
                    userMessage,
                    routerResult.getQueryInterpretation(),
                    routerResult.getTaskFamily(),
                    rerankedResults,
                    routerResult.getRetrievalMode()
            );

            return buildResponse(sessionId, draft, rerankedResults, routerResult);
        } catch (Exception e) {
            log.error("[Orchestrator] Error processing query", e);
            return ChatResponse.builder()
                    .sessionId(sessionId)
                    .answer("抱歉，处理您的请求时出现错误，请稍后再试。")
                    .taskFamily("ERROR")
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    private RouterResult route(String sessionId, String userMessage) {
        RouterRequest request = RouterRequest.builder().sessionId(sessionId).query(userMessage).build();
        return routerService.route(request);
    }

    private List<RetrievalResult> retrieve(String query, RouterResult routerResult) {
        String retrievalMode = routerResult.getRetrievalMode();
        if ("NONE".equalsIgnoreCase(retrievalMode)) {
            return new ArrayList<>();
        }
        if ("HYBRID".equalsIgnoreCase(retrievalMode)) {
            return retrieveTool.hybridRetrieve(query, 10);
        }
        return retrieveTool.retrieveNews(query, 10);
    }

    private List<RetrievalResult> rerank(String query, List<RetrievalResult> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        return rerankTool.mmrRerank(candidates, 5);
    }

    private boolean requiresPlanner(String taskFamily) {
        return "COMPARE".equals(taskFamily) || "DEEP_DIVE".equals(taskFamily);
    }

    private ChatResponse buildResponse(String sessionId, GeneratorDraft draft, List<RetrievalResult> sources, RouterResult routerResult) {
        List<ChatResponse.Source> sourcesDto = sources.stream().limit(3).map(r -> ChatResponse.Source.builder()
                .id(r.getId())
                .title(r.getTitle())
                .url(r.getUrl() != null ? r.getUrl() : "")
                .relevance(r.getScore())
                .build()).toList();

        String mergedAnswer = "";
        if (draft != null && draft.getAnswerItems() != null) {
            mergedAnswer = draft.getAnswerItems().stream()
                    .map(GeneratorDraft.AnswerItem::getText)
                    .filter(text -> text != null && !text.isBlank())
                    .collect(Collectors.joining("\n"));
        }

        return ChatResponse.builder()
                .sessionId(sessionId)
                .answer(mergedAnswer)
                .taskFamily(routerResult.getTaskFamily())
                .sources(sourcesDto)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
