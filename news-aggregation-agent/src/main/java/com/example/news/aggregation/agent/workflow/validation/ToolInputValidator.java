package com.example.news.aggregation.agent.workflow.validation;

import com.example.news.aggregation.agent.workflow.WorkflowContext;
import com.example.news.aggregation.agent.workflow.WorkflowStep;
import com.example.news.aggregation.llm.springai.contract.ExecutionEnums;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具输入校验器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolInputValidator {

    private final SchemaValidationProperties properties;

    public void validate(WorkflowStep step, WorkflowContext context) {
        if (!properties.isEnabled()) {
            return;
        }
        if (step == null) {
            throw new ToolValidationException(
                    ExecutionEnums.ErrorCategory.RETRYABLE_TOOL_ERROR,
                    "input_schema_fail",
                    "步骤为空，无法执行输入校验"
            );
        }
        recordValidationContext(step, context);
        syncStepRuntimeContext(step, context);
        validateSchemaVersion(step, context);
        validateBaseFields(step);
        validateCapabilityInputs(step, context);
    }

    private void validateSchemaVersion(WorkflowStep step, WorkflowContext context) {
        String schemaVersion = firstNonBlank(
                step.getSchemaVersion(),
                readContextAsString(context, "workflow.schema.version"),
                properties.getDefaultSchemaVersion()
        );
        List<String> supported = properties.getSupportedSchemaVersions();
        if (schemaVersion == null || schemaVersion.isBlank() || supported == null || supported.isEmpty()) {
            return;
        }
        if (context != null) {
            context.putAttribute("workflow.schema.version", schemaVersion);
        }
        if (supported.contains(schemaVersion)) {
            return;
        }
        String message = String.format("schema_version不支持: stepId=%s, capability=%s, schemaVersion=%s, supported=%s",
                step.getStepId(), step.getCapabilityName(), schemaVersion, supported);
        if (isCompatMode(step)) {
            log.warn("[validator][input] 兼容模式受限放行: {}", message);
            markQualityWarning(context, "schema_version_incompatible_restricted");
            throw new ToolValidationException(
                    ExecutionEnums.ErrorCategory.QUALITY_FAIL,
                    "schema_version_unsupported_compat_restricted",
                    message
            );
        }
        throw new ToolValidationException(
                ExecutionEnums.ErrorCategory.POLICY_QUOTA_AUTH,
                "schema_version_unsupported",
                message
        );
    }

    private void validateBaseFields(WorkflowStep step) {
        if (isBlank(step.getStepId())) {
            throw new ToolValidationException(
                    ExecutionEnums.ErrorCategory.RETRYABLE_TOOL_ERROR,
                    "input_schema_fail",
                    "stepId为空"
            );
        }
        if (isBlank(step.getCapabilityName())) {
            throw new ToolValidationException(
                    ExecutionEnums.ErrorCategory.RETRYABLE_TOOL_ERROR,
                    "input_schema_fail",
                    "capabilityName为空"
            );
        }
    }

    private void validateCapabilityInputs(WorkflowStep step, WorkflowContext context) {
        Map<String, Object> parameters = step.getParameters();
        String capability = step.getCapabilityName();
        if ("search_news".equals(capability) || "retrieve_news".equals(capability)) {
            String query = firstNonBlank(readAsString(parameters, "query"), context != null ? context.getQuery() : null);
            requireNonBlank(query, "query", step, ExecutionEnums.ErrorCategory.NEED_USER_INPUT);
            return;
        }
        if ("llm_generate".equals(capability)) {
            String query = firstNonBlank(readAsString(parameters, "query"), context != null ? context.getQuery() : null);
            requireNonBlank(query, "query", step, ExecutionEnums.ErrorCategory.NEED_USER_INPUT);
            String taskFamily = firstNonBlank(readAsString(parameters, "taskFamily"), context != null ? context.getTaskFamily() : null);
            requireNonBlank(taskFamily, "taskFamily", step, ExecutionEnums.ErrorCategory.NEED_USER_INPUT);
            return;
        }
        if ("rerank_results".equals(capability)) {
            int evidenceCount = context != null && context.getEvidence() != null ? context.getEvidence().size() : 0;
            if (evidenceCount <= 0) {
                throw new ToolValidationException(
                        ExecutionEnums.ErrorCategory.QUALITY_FAIL,
                        "input_data_insufficient",
                        String.format("步骤缺少重排输入证据: stepId=%s, capability=%s", step.getStepId(), capability)
                );
            }
        }
    }

    private void requireNonBlank(String value,
                                 String field,
                                 WorkflowStep step,
                                 ExecutionEnums.ErrorCategory category) {
        if (!isBlank(value)) {
            return;
        }
        throw new ToolValidationException(
                category,
                "missing_required_input",
                String.format("缺少必填输入: stepId=%s, capability=%s, field=%s",
                        step.getStepId(), step.getCapabilityName(), field)
        );
    }

    private boolean isCompatMode(WorkflowStep step) {
        if (properties.getMode() != SchemaValidationMode.COMPAT) {
            return false;
        }
        List<String> tools = properties.getCompatibilityTools();
        return tools == null || tools.isEmpty() || tools.contains(step.getCapabilityName());
    }

    @SuppressWarnings("unchecked")
    private void markQualityWarning(WorkflowContext context, String warning) {
        if (!properties.isQualityGateEnabled() || context == null) {
            return;
        }
        context.putAttribute("workflow.quality.gate", true);
        Object raw = context.getAttributes().get("workflow.quality.warnings");
        List<String> warnings;
        if (raw instanceof List<?> list) {
            warnings = (List<String>) list;
        } else {
            warnings = new java.util.ArrayList<>();
            context.putAttribute("workflow.quality.warnings", warnings);
        }
        warnings.add(warning);
        context.putAttribute("workflow.quality.warning.count", warnings.size());
    }

    private String readAsString(Map<String, Object> map, String key) {
        if (map == null || key == null || !map.containsKey(key) || map.get(key) == null) {
            return null;
        }
        return String.valueOf(map.get(key));
    }

    private String readContextAsString(WorkflowContext context, String key) {
        if (context == null || context.getAttributes() == null || key == null) {
            return null;
        }
        Object value = context.getAttributes().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void recordValidationContext(WorkflowStep step, WorkflowContext context) {
        if (context == null) {
            return;
        }
        context.putAttribute("workflow.schema.validation.mode", properties.getMode().name());
        if (!isBlank(step.getSchemaVersion())) {
            context.putAttribute("workflow.schema.version", step.getSchemaVersion());
        }
        if (!isBlank(step.getSemanticVersion())) {
            context.putAttribute("workflow.semantic.version", step.getSemanticVersion());
        }
        Object rawCount = context.getAttributes().get("workflow.validation.input.checked.count");
        int count = rawCount instanceof Number number ? number.intValue() : 0;
        context.putAttribute("workflow.validation.input.checked.count", count + 1);
    }

    /**
     * 同步 step_run 运行时信息，供决策与观测使用。
     */
    @SuppressWarnings("unchecked")
    private void syncStepRuntimeContext(WorkflowStep step, WorkflowContext context) {
        if (context == null || context.getAttributes() == null || step == null || step.getStepId() == null) {
            return;
        }
        Object raw = context.getAttributes().get("workflow.runtime.step." + step.getStepId());
        if (!(raw instanceof Map<?, ?> runtimeMapRaw)) {
            return;
        }
        Map<String, Object> runtimeMap = (Map<String, Object>) runtimeMapRaw;
        Object attempt = runtimeMap.get("attempt");
        Object maxRetries = runtimeMap.get("maxRetries");
        Object status = runtimeMap.get("status");
        context.putAttribute("workflow.runtime.step.attempt." + step.getStepId(), attempt);
        context.putAttribute("workflow.runtime.step.maxRetries." + step.getStepId(), maxRetries);
        context.putAttribute("workflow.runtime.step.status." + step.getStepId(), status);
    }
}
