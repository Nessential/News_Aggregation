package com.example.news.aggregation.llm.springai.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.example.news.aggregation.llm.springai.node.DraftGenerateNode;
import com.example.news.aggregation.llm.springai.node.QualityCheckNode;
import com.example.news.aggregation.llm.springai.node.SelfCritiqueNode;
import com.example.news.aggregation.llm.springai.state.GeneratorState;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeneratorGraph {

    private final DraftGenerateNode draftGenerateNode;
    private final SelfCritiqueNode selfCritiqueNode;
    private final QualityCheckNode qualityCheckNode;

    private StateGraph graph;
    private CompiledGraph compiledGraph;

    @PostConstruct
    public void init() {
        try {
            graph = new StateGraph();

            graph.addNode("draft_generate", state -> CompletableFuture.completedFuture(
                    updateState(state, draftGenerateNode.execute(readState(state)))
            ));
            graph.addNode("self_critique", state -> CompletableFuture.completedFuture(
                    updateState(state, selfCritiqueNode.execute(readState(state)))
            ));
            graph.addNode("quality_check", state -> CompletableFuture.completedFuture(
                    updateState(state, qualityCheckNode.execute(readState(state)))
            ));

            graph.addEdge(StateGraph.START, "draft_generate");
            graph.addEdge("draft_generate", "self_critique");
            graph.addEdge("self_critique", "quality_check");

            graph.addConditionalEdges("quality_check",
                    state -> CompletableFuture.completedFuture(
                            shouldRetry(readState(state)) ? "retry" : "done"
                    ),
                    Map.of(
                            "retry", "draft_generate",
                            "done", StateGraph.END
                    )
            );

            this.compiledGraph = graph.compile();
            log.info("GeneratorGraph initialized");
        } catch (GraphStateException e) {
            throw new IllegalStateException("GeneratorGraph init failed", e);
        }
    }

    public GeneratorState invoke(GeneratorState state) {
        Map<String, Object> initial = new HashMap<>();
        initial.put("generatorState", state);

        Optional<OverAllState> result = compiledGraph.invoke(initial);
        if (result.isPresent()) {
            return readState(result.get());
        }
        return state;
    }

    private GeneratorState readState(OverAllState state) {
        return state.value("generatorState", GeneratorState.class).orElse(new GeneratorState());
    }

    private Map<String, Object> updateState(OverAllState state, GeneratorState newState) {
        Map<String, Object> update = new HashMap<>();
        update.put("generatorState", newState);
        return update;
    }

    private boolean shouldRetry(GeneratorState state) {
        if (state == null) {
            return false;
        }
        return Boolean.FALSE.equals(state.getValidated());
    }
}
