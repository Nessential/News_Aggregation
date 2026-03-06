package com.example.news.aggregation.agent.workflow;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工作流启动注册器。
 * 启动时注册内置工作流（QA/SUMMARY/TIMELINE）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowBootstrap {

    private final WorkflowRegistry workflowRegistry;

    @PostConstruct
    public void registerBuiltinWorkflows() {
        workflowRegistry.registerWorkflow(buildQaWorkflow());
        workflowRegistry.registerWorkflow(buildSummaryWorkflow());
        workflowRegistry.registerWorkflow(buildTimelineWorkflow());
        log.info("Builtin workflows registered.");
    }

    private WorkflowDefinition buildQaWorkflow() {
        return WorkflowDefinition.builder()
                .id("QA_WORKFLOW")
                .name("新闻问答工作流")
                .steps(List.of(
                        WorkflowStep.builder()
                                .stepId("qa-retrieve")
                                .capabilityName("retrieve_news")
                                .parameters(Map.of("mode", "HYBRID", "topK", 10))
                                .outputSchema(itemsOutputSchema())
                                .build(),
                        WorkflowStep.builder()
                                .stepId("qa-rerank")
                                .capabilityName("rerank_results")
                                .parameters(Map.of("topK", 5, "lambda", 0.7))
                                .outputSchema(itemsOutputSchema())
                                .build(),
                        WorkflowStep.builder()
                                .stepId("qa-generate")
                                .capabilityName("llm_generate")
                                .parameters(Map.of("taskFamily", "QA"))
                                .outputSchema(answerOutputSchema())
                                .build()
                ))
                .metadata(Map.of("type", "QA"))
                .build();
    }

    private WorkflowDefinition buildSummaryWorkflow() {
        return WorkflowDefinition.builder()
                .id("SUMMARY_WORKFLOW")
                .name("新闻摘要工作流")
                .steps(List.of(
                        WorkflowStep.builder()
                                .stepId("summary-retrieve")
                                .capabilityName("retrieve_news")
                                .parameters(Map.of("mode", "HYBRID", "topK", 10))
                                .outputSchema(itemsOutputSchema())
                                .build(),
                        WorkflowStep.builder()
                                .stepId("summary-generate")
                                .capabilityName("llm_generate")
                                .parameters(Map.of("taskFamily", "SUMMARIZE"))
                                .outputSchema(answerOutputSchema())
                                .build()
                ))
                .metadata(Map.of("type", "SUMMARY"))
                .build();
    }

    private WorkflowDefinition buildTimelineWorkflow() {
        return WorkflowDefinition.builder()
                .id("TIMELINE_WORKFLOW")
                .name("新闻时间线工作流")
                .steps(List.of(
                        WorkflowStep.builder()
                                .stepId("timeline-retrieve")
                                .capabilityName("retrieve_news")
                                .parameters(Map.of("mode", "HYBRID", "topK", 15))
                                .outputSchema(itemsOutputSchema())
                                .build(),
                        WorkflowStep.builder()
                                .stepId("timeline-rerank")
                                .capabilityName("rerank_results")
                                .parameters(Map.of("topK", 8, "lambda", 0.7))
                                .outputSchema(itemsOutputSchema())
                                .build(),
                        WorkflowStep.builder()
                                .stepId("timeline-generate")
                                .capabilityName("llm_generate")
                                .parameters(Map.of("taskFamily", "TIMELINE"))
                                .outputSchema(answerOutputSchema())
                                .build()
                ))
                .metadata(Map.of("type", "TIMELINE"))
                .build();
    }

    private Map<String, Object> itemsOutputSchema() {
        return Map.of(
                "type", "object",
                "required", List.of("items")
        );
    }

    private Map<String, Object> answerOutputSchema() {
        return Map.of(
                "type", "object",
                "required", List.of("answer")
        );
    }
}
