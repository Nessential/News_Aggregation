package com.example.news.aggregation.llm.springai.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.example.news.aggregation.llm.springai.node.DependencyAnalysisNode;
import com.example.news.aggregation.llm.springai.node.ExecutionPlanBuilderNode;
import com.example.news.aggregation.llm.springai.node.ResourceEstimationNode;
import com.example.news.aggregation.llm.springai.node.TaskDecompositionNode;
import com.example.news.aggregation.llm.springai.state.PlannerState;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * PlannerGraph
 * 线性流水线：TaskDecomposition -> DependencyAnalysis -> ResourceEstimation -> PlanBuild
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerGraph {

    private final TaskDecompositionNode taskDecompositionNode;
    private final DependencyAnalysisNode dependencyAnalysisNode;
    private final ResourceEstimationNode resourceEstimationNode;
    private final ExecutionPlanBuilderNode executionPlanBuilderNode;

    private StateGraph graph;
    private CompiledGraph compiledGraph;

    @PostConstruct
    public void init() {
        try {
            graph = new StateGraph();

            graph.addNode("task_decompose", state -> CompletableFuture.completedFuture(
                    updateState(state, taskDecompositionNode.execute(readState(state)))
            ));
            graph.addNode("dependency_analyze", state -> CompletableFuture.completedFuture(
                    updateState(state, dependencyAnalysisNode.execute(readState(state)))
            ));
            graph.addNode("resource_estimate", state -> CompletableFuture.completedFuture(
                    updateState(state, resourceEstimationNode.execute(readState(state)))
            ));
            graph.addNode("plan_build", state -> CompletableFuture.completedFuture(
                    updateState(state, executionPlanBuilderNode.execute(readState(state)))
            ));

            graph.addEdge(StateGraph.START, "task_decompose");
            graph.addEdge("task_decompose", "dependency_analyze");
            graph.addEdge("dependency_analyze", "resource_estimate");
            graph.addEdge("resource_estimate", "plan_build");
            graph.addEdge("plan_build", StateGraph.END);

            this.compiledGraph = graph.compile();
            log.info("PlannerGraph initialized");
        } catch (GraphStateException e) {
            throw new IllegalStateException("PlannerGraph init failed", e);
        }
    }

    /**
     * 执行Graph
     *
     * @param state 初始状态
     * @return 最终状态
     */
    public PlannerState invoke(PlannerState state) {
        Map<String, Object> initial = new HashMap<>();
        initial.put("plannerState", state);

        Optional<OverAllState> result = compiledGraph.invoke(initial);
        if (result.isPresent()) {
            return readState(result.get());
        }
        return state;
    }

    private PlannerState readState(OverAllState state) {
        return state.value("plannerState", PlannerState.class).orElse(new PlannerState());
    }

    private Map<String, Object> updateState(OverAllState state, PlannerState newState) {
        Map<String, Object> update = new HashMap<>();
        update.put("plannerState", newState);
        return update;
    }
}
