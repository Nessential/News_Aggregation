package com.example.news.aggregation.llm.springai.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.example.news.aggregation.llm.springai.node.CompletenessCheckNode;
import com.example.news.aggregation.llm.springai.node.IntentAnalyzeNode;
import com.example.news.aggregation.llm.springai.node.ReferenceResolveNode;
import com.example.news.aggregation.llm.springai.node.SlotExtractionNode;
import com.example.news.aggregation.llm.springai.state.RouterState;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * RouterGraph。
 * 线性流程 + 条件分支：IntentAnalyze -> ReferenceResolve -> (SlotExtraction?) -> CompletenessCheck。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouterGraph {

    private final IntentAnalyzeNode intentAnalyzeNode;
    private final ReferenceResolveNode referenceResolveNode;
    private final SlotExtractionNode slotExtractionNode;
    private final CompletenessCheckNode completenessCheckNode;

    private StateGraph graph;
    private CompiledGraph compiledGraph;

    @PostConstruct
    public void init() {
        try {
            graph = new StateGraph();

            graph.addNode("intent_analyze", state -> CompletableFuture.completedFuture(
                    updateState(state, intentAnalyzeNode.execute(readState(state)))
            ));
            graph.addNode("reference_resolve", state -> CompletableFuture.completedFuture(
                    updateState(state, referenceResolveNode.execute(readState(state)))
            ));
            graph.addNode("slot_extract", state -> CompletableFuture.completedFuture(
                    updateState(state, slotExtractionNode.execute(readState(state)))
            ));
            graph.addNode("completeness_check", state -> CompletableFuture.completedFuture(
                    updateState(state, completenessCheckNode.execute(readState(state)))
            ));

            graph.addEdge(StateGraph.START, "intent_analyze");
            // L1 语义路由：新闻相关才进入后续节点
            graph.addConditionalEdges("intent_analyze",
                    state -> CompletableFuture.completedFuture(
                            shouldProceedForNews(readState(state)) ? "news" : "non_news"
                    ),
                    Map.of(
                            "news", "reference_resolve",
                            "non_news", StateGraph.END
                    )
            );

            // 根据指代消解结果决定是否执行槽位提取，避免空查询导致的冗余处理
            graph.addConditionalEdges("reference_resolve",
                    state -> CompletableFuture.completedFuture(
                            shouldExtractSlots(readState(state)) ? "extract" : "skip"
                    ),
                    Map.of(
                            "extract", "slot_extract",
                            "skip", "completeness_check"
                    )
            );

            graph.addEdge("slot_extract", "completeness_check");
            graph.addEdge("completeness_check", StateGraph.END);

            this.compiledGraph = graph.compile();
            log.info("RouterGraph initialized");
        } catch (GraphStateException e) {
            throw new IllegalStateException("RouterGraph init failed", e);
        }
    }

    /**
     * 执行图。
     *
     * @param state 初始状态
     * @return 最终状态
     */
    public RouterState invoke(RouterState state) {
        Map<String, Object> initial = new HashMap<>();
        initial.put("routerState", state);

        String sessionId = state != null && state.getSessionId() != null ? state.getSessionId() : "unknown";
        // 流程日志：RouterGraph 开始
        log.info("[链路最终] RouterGraph开始FLOW|router|graph=RouterGraph|step=start|sessionId={}|next=intent_analyze", sessionId);

        Optional<OverAllState> result = compiledGraph.invoke(initial);
        if (result.isPresent()) {
            RouterState finalState = readState(result.get());
            log.info("[链路最终] RouterGraph结束FLOW|router|graph=RouterGraph|step=end|sessionId={}|intentScope={}|taskFamily={}|retrievalMode={}|needsClarification={}",
                    sessionId,
                    finalState.getIntentScope(),
                    finalState.getTaskFamily(),
                    finalState.getRetrievalMode(),
                    finalState.getNeedsClarification());
            return finalState;
        }
        log.warn("RouterGraph结束-无结果FLOW|router|graph=RouterGraph|step=end|sessionId={}|result=empty", sessionId);
        return state;
    }

    private RouterState readState(OverAllState state) {
        return state.value("routerState", RouterState.class).orElse(new RouterState());
    }

    private Map<String, Object> updateState(OverAllState state, RouterState newState) {
        Map<String, Object> update = new HashMap<>();
        update.put("routerState", newState);
        return update;
    }

    /**
     * 判断是否需要槽位提取。
     *
     * @param state Router 状态
     * @return true=执行槽位提取
     */
    private boolean shouldExtractSlots(RouterState state) {
        if (state == null) {
            return false;
        }
        String resolvedQuery = state.getResolvedQuery();
        boolean shouldExtract = resolvedQuery != null && !resolvedQuery.isBlank();
        String sessionId = state.getSessionId() != null ? state.getSessionId() : "unknown";
        String nextNode = shouldExtract ? "slot_extract" : "completeness_check";
        String reason = shouldExtract ? "resolvedQuery 非空" : "resolvedQuery 为空";
        log.info("[链路最终] 节点跳转判定FLOW|router|from=reference_resolve|to={}|reason={}|sessionId={}|shouldExtract={}|resolvedQueryBlank={}",
                nextNode, reason, sessionId, shouldExtract, resolvedQuery == null || resolvedQuery.isBlank());
        return shouldExtract;
    }

    private boolean shouldProceedForNews(RouterState state) {
        if (state == null) {
            return true;
        }
        String scope = state.getIntentScope();
        boolean isNews = scope == null || !"NON_NEWS".equalsIgnoreCase(scope);
        String sessionId = state.getSessionId() != null ? state.getSessionId() : "unknown";
        log.info("[链路最终] 节点跳转判定FLOW|router|from=intent_analyze|to={}|reason=intentScope={}|sessionId={}",
                isNews ? "reference_resolve" : "END", scope, sessionId);
        return isNews;
    }
}