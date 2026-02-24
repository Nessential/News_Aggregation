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
 * RouterGraph
 * 线性流程 + 条件分支：IntentAnalyze -> ReferenceResolve -> (SlotExtraction?) -> CompletenessCheck
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
            graph.addEdge("intent_analyze", "reference_resolve");

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
     * 执行图
     *
     * @param state 初始状态
     * @return 最终状态
     */
    public RouterState invoke(RouterState state) {
        Map<String, Object> initial = new HashMap<>();
        initial.put("routerState", state);

        Optional<OverAllState> result = compiledGraph.invoke(initial);
        if (result.isPresent()) {
            return readState(result.get());
        }
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
     * 判断是否需要槽位提取
     *
     * @param state Router状态
     * @return true=执行槽位提取
     */
    private boolean shouldExtractSlots(RouterState state) {
        if (state == null) {
            return false;
        }
        String resolvedQuery = state.getResolvedQuery();
        return resolvedQuery != null && !resolvedQuery.isBlank();
    }
}