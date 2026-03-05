package com.example.news.aggregation.agent.workflow.validation;

import com.example.news.aggregation.agent.workflow.WorkflowContext;
import com.example.news.aggregation.agent.workflow.WorkflowStep;
import com.example.news.aggregation.llm.springai.contract.ExecutionEnums;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolInputValidatorTest {

    @Test
    void shouldFailWhenSchemaVersionUnsupportedInStrictMode() {
        SchemaValidationProperties properties = new SchemaValidationProperties();
        properties.setMode(SchemaValidationMode.STRICT);
        properties.setSupportedSchemaVersions(new ArrayList<>(List.of("execution-plan/1.0")));
        ToolInputValidator validator = new ToolInputValidator(properties);

        WorkflowStep step = WorkflowStep.builder()
                .stepId("s1")
                .capabilityName("search_news")
                .schemaVersion("execution-plan/2.0")
                .parameters(Map.of("query", "ai"))
                .build();

        ToolValidationException ex = assertThrows(ToolValidationException.class,
                () -> validator.validate(step, WorkflowContext.builder().query("ai").build()));
        assertEquals(ExecutionEnums.ErrorCategory.POLICY_QUOTA_AUTH, ex.getErrorCategory());
        assertEquals("schema_version_unsupported", ex.getReasonCode());
    }

    @Test
    void shouldFailRestrictedWhenSchemaVersionUnsupportedInCompatMode() {
        SchemaValidationProperties properties = new SchemaValidationProperties();
        properties.setMode(SchemaValidationMode.COMPAT);
        properties.setSupportedSchemaVersions(new ArrayList<>(List.of("execution-plan/1.0")));
        properties.setQualityGateEnabled(true);
        ToolInputValidator validator = new ToolInputValidator(properties);

        WorkflowStep step = WorkflowStep.builder()
                .stepId("s1")
                .capabilityName("search_news")
                .schemaVersion("execution-plan/2.0")
                .parameters(Map.of("query", "ai"))
                .build();
        WorkflowContext context = WorkflowContext.builder().query("ai").build();

        ToolValidationException ex = assertThrows(ToolValidationException.class,
                () -> validator.validate(step, context));
        assertEquals(ExecutionEnums.ErrorCategory.QUALITY_FAIL, ex.getErrorCategory());
        assertEquals("schema_version_unsupported_compat_restricted", ex.getReasonCode());
        assertEquals(Boolean.TRUE, context.getAttributes().get("workflow.quality.gate"));
        assertTrue(((List<?>) context.getAttributes().get("workflow.quality.warnings")).size() >= 1);
    }

    @Test
    void shouldFailWhenRequiredQueryMissing() {
        SchemaValidationProperties properties = new SchemaValidationProperties();
        ToolInputValidator validator = new ToolInputValidator(properties);

        WorkflowStep step = WorkflowStep.builder()
                .stepId("s1")
                .capabilityName("search_news")
                .schemaVersion("execution-plan/1.0")
                .parameters(Map.of())
                .build();

        ToolValidationException ex = assertThrows(ToolValidationException.class,
                () -> validator.validate(step, WorkflowContext.builder().build()));
        assertEquals(ExecutionEnums.ErrorCategory.NEED_USER_INPUT, ex.getErrorCategory());
        assertEquals("missing_required_input", ex.getReasonCode());
    }
}
