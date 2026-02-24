package com.example.news.aggregation.llm.springai.orchestrator;

import com.example.news.aggregation.llm.springai.config.GraphProperties;
import com.example.news.aggregation.llm.springai.contract.ChatResponse;
import com.example.news.aggregation.llm.springai.contract.GeneratorDraft;
import com.example.news.aggregation.llm.springai.contract.Plan;
import com.example.news.aggregation.llm.springai.contract.PlanRequest;
import com.example.news.aggregation.llm.springai.contract.RouterRequest;
import com.example.news.aggregation.llm.springai.contract.RouterResult;
import com.example.news.aggregation.llm.springai.service.GeneratorService;
import com.example.news.aggregation.llm.springai.service.PlannerService;
import com.example.news.aggregation.llm.springai.service.RouterService;
import com.example.news.aggregation.llm.springai.tool.RetrieveTool;
import com.example.news.aggregation.llm.springai.tool.RerankTool;
import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM编排器（Graph + Tool统一入口）
 * 负责 Router -> Planner（可选）-> Retrieve -> Rerank -> Generator
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMOrchestrator {

    /** Router服务（基于RouterGraph） */
    private final RouterService routerService;
    /** Planner服务（基于PlannerGraph） */
    private final PlannerService plannerService;
    /** Generator服务（基于GeneratorGraph） */
    private final GeneratorService generatorService;
    /** 检索工具（向量+混合检索） */
    private final RetrieveTool retrieveTool;
    /** 重排工具（MMR） */
    private final RerankTool rerankTool;
    /** Graph配置 */
    private final GraphProperties graphProperties;

    /**
     * 处理用户查询（端到端）
     *
     * @param sessionId 会话ID
     * @param userMessage 用户消息
     * @param userId 用户ID
     * @return 聊天响应
     */
    public ChatResponse processQuery(String sessionId, String userMessage, String userId) {
        log.info("[Orchestrator] Processing query: sessionId={}, userId={}, message={}",
                sessionId, userId, userMessage);

        try {
            // 1) Router
            RouterResult routerResult = route(sessionId, userMessage);

            // 2) 澄清
            if (Boolean.TRUE.equals(routerResult.getNeedsClarification())) {
                return ChatResponse.builder()
                        .sessionId(sessionId)
                        .answer(routerResult.getClarificationQuestion())
                        .taskFamily(routerResult.getTaskFamily())
                        .timestamp(System.currentTimeMillis())
                        .build();
            }

            // 3) Planner（可选）
            Plan plan = null;
            if (graphProperties.isPlannerEnabled() && requiresPlanner(routerResult.getTaskFamily())) {
                plan = plannerService.plan(PlanRequest.builder()
                        .query(userMessage)
                        .routerResult(routerResult)
                        .build());
                log.info("[Orchestrator] Planner generated plan: tasks={}",
                        plan.getTasks() != null ? plan.getTasks().size() : 0);
            }

            // 4) 检索与重排
            List<RetrievalResult> retrievalResults = retrieve(userMessage, routerResult);
            List<RetrievalResult> rerankedResults = rerank(userMessage, retrievalResults);

            // 5) Generator
            GeneratorDraft draft = generatorService.generate(
                    userMessage,
                    routerResult.getTaskFamily(),
                    rerankedResults
            );

            // 6) 构建响应
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

    /**
     * Router入口
     */
    private RouterResult route(String sessionId, String userMessage) {
        RouterRequest request = RouterRequest.builder()
                .sessionId(sessionId)
                .query(userMessage)
                .build();
        return routerService.route(request);
    }

    /**
     * 检索相关文档
     */
    private List<RetrievalResult> retrieve(String query, RouterResult routerResult) {
        String retrievalMode = routerResult.getRetrievalMode();
        if ("HYBRID".equalsIgnoreCase(retrievalMode)) {
            return retrieveTool.hybridRetrieve(query, 10);
        }
        return retrieveTool.retrieveNews(query, 10);
    }

    /**
     * 重排文档
     */
    private List<RetrievalResult> rerank(String query, List<RetrievalResult> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        return rerankTool.mmrRerank(candidates, 5);
    }

    /**
     * 判断是否需要Planner
     */
    private boolean requiresPlanner(String taskFamily) {
        return "COMPARE".equals(taskFamily)
                || "DEEP_DIVE".equals(taskFamily);
    }

    /**
     * 构建最终响应
     */
    private ChatResponse buildResponse(String sessionId, GeneratorDraft draft,
                                       List<RetrievalResult> sources, RouterResult routerResult) {
        List<ChatResponse.Source> sourcesDto = sources.stream()
                .limit(3)
                .map(r -> ChatResponse.Source.builder()
                        .id(r.getId())
                        .title(r.getTitle())
                        .url(r.getUrl() != null ? r.getUrl() : "")
                        .relevance(r.getScore())
                        .build())
                .toList();

        return ChatResponse.builder()
                .sessionId(sessionId)
                .answer(draft.getAnswer())
                .taskFamily(routerResult.getTaskFamily())
                .sources(sourcesDto)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}