package com.example.news.aggregation.agent.workflow.validation;

import com.example.news.aggregation.agent.tool.dto.RetrievalResult;
import com.example.news.aggregation.agent.execution.service.StepClaimService;
import com.example.news.aggregation.agent.workflow.WorkflowContext;
import com.example.news.aggregation.agent.workflow.WorkflowStep;
import com.example.news.aggregation.llm.springai.contract.DoneCheckRule;
import com.example.news.aggregation.llm.springai.contract.ExecutionEnums;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ToolOutputValidatorTest {

    @Test
    void shouldPassWhenListOutputMeetsSchema() {
        SchemaValidationProperties properties = new SchemaValidationProperties();
        ToolOutputValidator validator = new ToolOutputValidator(properties, mock(StepClaimService.class));
        WorkflowStep step = WorkflowStep.builder()
                .stepId("s1")
                .capabilityName("search_news")
                .outputSchema(Map.of("type", "object", "required", List.of("items")))
                .doneCheck(DoneCheckRule.builder()
                        .expression("items_count >= 1")
                        .build())
                .build();
        WorkflowContext context = WorkflowContext.builder().build();

        validator.validate(step, context, List.of("a"));
    }

    @Test
    void shouldFailOutputSchemaInStrictMode() {
        SchemaValidationProperties properties = new SchemaValidationProperties();
        properties.setMode(SchemaValidationMode.STRICT);
        ToolOutputValidator validator = new ToolOutputValidator(properties, mock(StepClaimService.class));
        WorkflowStep step = WorkflowStep.builder()
                .stepId("s1")
                .capabilityName("llm_generate")
                .outputSchema(Map.of("type", "object", "required", List.of("answer", "citations")))
                .build();

        ToolValidationException ex = assertThrows(ToolValidationException.class,
                () -> validator.validate(step, WorkflowContext.builder().build(), List.of("x")));
        assertEquals(ExecutionEnums.ErrorCategory.RETRYABLE_TOOL_ERROR, ex.getErrorCategory());
        assertEquals("output_type_mismatch_stable", ex.getReasonCode());
    }

    @Test
    void shouldFailDoneCheckAsQualityFail() {
        SchemaValidationProperties properties = new SchemaValidationProperties();
        ToolOutputValidator validator = new ToolOutputValidator(properties, mock(StepClaimService.class));
        WorkflowStep step = WorkflowStep.builder()
                .stepId("s1")
                .capabilityName("llm_generate")
                .outputSchema(Map.of("type", "object", "required", List.of("answer")))
                .doneCheck(DoneCheckRule.builder()
                        .expression("answer_not_blank")
                        .build())
                .build();

        ToolValidationException ex = assertThrows(ToolValidationException.class,
                () -> validator.validate(step, WorkflowContext.builder().build(), " "));
        assertEquals(ExecutionEnums.ErrorCategory.QUALITY_FAIL, ex.getErrorCategory());
        assertEquals("done_check_fail", ex.getReasonCode());
    }

    @Test
    void shouldAllowMissingOptionalFieldInCompatModeAndMarkQualityGate() {
        SchemaValidationProperties properties = new SchemaValidationProperties();
        properties.setMode(SchemaValidationMode.COMPAT);
        properties.setCompatibilityTools(new ArrayList<>(List.of("llm_generate")));
        properties.setOptionalOutputFieldsByTool(Map.of("llm_generate", List.of("citations")));
        ToolOutputValidator validator = new ToolOutputValidator(properties, mock(StepClaimService.class));
        WorkflowStep step = WorkflowStep.builder()
                .stepId("s1")
                .capabilityName("llm_generate")
                .outputSchema(Map.of("type", "object", "required", List.of("answer", "citations")))
                .doneCheck(DoneCheckRule.builder()
                        .expression("answer_not_blank")
                        .build())
                .build();
        WorkflowContext context = WorkflowContext.builder().build();

        validator.validate(step, context, Map.of("answer", "ok"));
        assertEquals(Boolean.TRUE, context.getAttributes().get("workflow.quality.gate"));
        assertTrue(((List<?>) context.getAttributes().get("workflow.quality.warnings")).size() >= 1);
    }

    @Test
    void shouldCountEvidenceByArticleIdWithSnippetRequired() {
        SchemaValidationProperties properties = new SchemaValidationProperties();
        ToolOutputValidator validator = new ToolOutputValidator(properties, mock(StepClaimService.class));
        WorkflowStep step = WorkflowStep.builder()
                .stepId("s1")
                .capabilityName("llm_generate")
                .outputSchema(Map.of("type", "object", "required", List.of("answer")))
                .doneCheck(DoneCheckRule.builder()
                        .minEvidenceCount(2)
                        .expression("answer_not_blank")
                        .build())
                .build();
        WorkflowContext context = WorkflowContext.builder()
                .evidence(new ArrayList<>(List.of(
                        RetrievalResult.builder().articleId(1L).matchedSnippet("a").build(),
                        RetrievalResult.builder().articleId(1L).matchedSnippet("b").build(),
                        RetrievalResult.builder().articleId(2L).fullContent("c").build(),
                        RetrievalResult.builder().articleId(3L).matchedSnippet(" ").build()
                )))
                .build();

        validator.validate(step, context, Map.of("answer", "ok"));
        assertEquals("article_id_dedup_with_snippet_required",
                context.getAttributes().get("workflow.evidence.standard"));
        assertEquals(4, context.getAttributes().get("workflow.evidence.raw.count"));
        assertEquals(2, context.getAttributes().get("workflow.evidence.normalized.count"));
    }

    @Test
    void shouldFailWhenOutputStringJsonParseError() {
        SchemaValidationProperties properties = new SchemaValidationProperties();
        ToolOutputValidator validator = new ToolOutputValidator(properties, mock(StepClaimService.class));
        WorkflowStep step = WorkflowStep.builder()
                .stepId("s1")
                .capabilityName("llm_generate")
                .outputSchema(Map.of("type", "object", "required", List.of("answer", "citations")))
                .build();

        ToolValidationException ex = assertThrows(ToolValidationException.class,
                () -> validator.validate(step, WorkflowContext.builder().build(), "{\"answer\":}"));
        assertEquals(ExecutionEnums.ErrorCategory.RETRYABLE_TOOL_ERROR, ex.getErrorCategory());
        assertEquals("output_parse_error", ex.getReasonCode());
    }

    @Test
    void shouldFailWhenOutputSchemaVersionMismatch() {
        SchemaValidationProperties properties = new SchemaValidationProperties();
        ToolOutputValidator validator = new ToolOutputValidator(properties, mock(StepClaimService.class));
        WorkflowStep step = WorkflowStep.builder()
                .stepId("s1")
                .capabilityName("llm_generate")
                .schemaVersion("execution-plan/1.0")
                .outputSchema(Map.of("type", "object", "required", List.of("answer")))
                .build();

        ToolValidationException ex = assertThrows(ToolValidationException.class,
                () -> validator.validate(step, WorkflowContext.builder().build(),
                        Map.of("answer", "ok", "schemaVersion", "execution-plan/2.0")));
        assertEquals(ExecutionEnums.ErrorCategory.QUALITY_FAIL, ex.getErrorCategory());
        assertEquals("output_schema_version_mismatch", ex.getReasonCode());
    }
}
