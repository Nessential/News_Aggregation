package com.example.news.aggregation.agent.workflow.validation;

import com.example.news.aggregation.agent.execution.service.StepClaimService;
import com.example.news.aggregation.agent.tool.dto.RetrievalResult;
import com.example.news.aggregation.agent.workflow.WorkflowContext;
import com.example.news.aggregation.agent.workflow.WorkflowStep;
import com.example.news.aggregation.llm.springai.contract.DoneCheckRule;
import com.example.news.aggregation.llm.springai.contract.ExecutionEnums;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具输出校验器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolOutputValidator {

    private static final Pattern ITEMS_COUNT_PATTERN = Pattern.compile("items_count\\s*>?=\\s*(\\d+)");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SchemaValidationProperties properties;
    private final StepClaimService stepClaimService;

    public void validate(WorkflowStep step, WorkflowContext context, Object output) {
        if (!properties.isEnabled() || step == null) {
            return;
        }
        recordValidationContext(context);
        Map<String, Object> normalized = normalizeOutput(step, output);
        persistOutputSnapshot(step, context, normalized);
        validateOutputSchemaVersion(step, context, normalized);
        validateOutputSchema(step, context, normalized);
        validateDoneCheck(step, context, normalized);
        log.debug("[validator][output] 输出校验通过|stepId={} |capability={}", step.getStepId(), step.getCapabilityName());
    }

    private void validateOutputSchemaVersion(WorkflowStep step,
                                             WorkflowContext context,
                                             Map<String, Object> normalized) {
        String expected = firstNonBlank(
                step.getSchemaVersion(),
                readContextAsString(context, "workflow.schema.version"),
                properties.getDefaultSchemaVersion()
        );
        if (expected == null || expected.isBlank()) {
            return;
        }
        String actual = firstNonBlank(
                readAsString(normalized, "schemaVersion"),
                readAsString(normalized, "schema_version")
        );
        if (actual == null || actual.isBlank()) {
            return;
        }
        if (expected.equals(actual)) {
            return;
        }
        throw new ToolValidationException(
                ExecutionEnums.ErrorCategory.QUALITY_FAIL,
                "output_schema_version_mismatch",
                String.format("输出schemaVersion不匹配: stepId=%s, capability=%s, expected=%s, actual=%s",
                        step.getStepId(), step.getCapabilityName(), expected, actual)
        );
    }

    private void validateOutputSchema(WorkflowStep step, WorkflowContext context, Map<String, Object> normalized) {
        Map<String, Object> schema = step.getOutputSchema();
        if (schema == null || schema.isEmpty()) {
            return;
        }
        Object required = schema.get("required");
        if (!(required instanceof List<?> requiredFields) || requiredFields.isEmpty()) {
            return;
        }

        for (Object fieldObj : requiredFields) {
            String field = String.valueOf(fieldObj);
            if (field.isBlank()) {
                continue;
            }
            if (isFieldPresent(field, normalized)) {
                continue;
            }
            if (canCompatSkipMissingField(step, field)) {
                markQualityWarning(context, "output_schema_compat_missing:" + field);
                continue;
            }
            throw new ToolValidationException(
                    ExecutionEnums.ErrorCategory.RETRYABLE_TOOL_ERROR,
                    "output_missing_required_field_stable",
                    String.format("输出schema校验失败: stepId=%s, capability=%s, missingField=%s",
                            step.getStepId(), step.getCapabilityName(), field)
            );
        }
    }

    private void validateDoneCheck(WorkflowStep step, WorkflowContext context, Map<String, Object> normalized) {
        DoneCheckRule doneCheck = step.getDoneCheck();
        List<String> requiredFields = doneCheck != null ? doneCheck.getRequiredFields() : List.of();
        if (requiredFields != null && !requiredFields.isEmpty()) {
            for (String field : requiredFields) {
                if (field == null || field.isBlank()) {
                    continue;
                }
                if (!isFieldPresent(field, normalized)) {
                    throw new ToolValidationException(
                            ExecutionEnums.ErrorCategory.QUALITY_FAIL,
                            "done_check_fail",
                            String.format("doneCheck字段校验失败: stepId=%s, capability=%s, missingField=%s",
                                    step.getStepId(), step.getCapabilityName(), field)
                    );
                }
            }
        }

        Integer minEvidence = doneCheck != null ? doneCheck.getMinEvidenceCount() : null;
        if (minEvidence != null && minEvidence > 0) {
            int evidenceCount = countNormalizedEvidenceSources(context);
            if (evidenceCount < minEvidence) {
                throw new ToolValidationException(
                        ExecutionEnums.ErrorCategory.QUALITY_FAIL,
                        "done_check_fail",
                        String.format("doneCheck证据不足: stepId=%s, capability=%s, need=%d, actual=%d",
                                step.getStepId(), step.getCapabilityName(), minEvidence, evidenceCount)
                );
            }
        }

        String expression = firstNonBlank(
                doneCheck != null ? doneCheck.getExpression() : null,
                step.getDoneCheckRef()
        );
        if (expression == null || expression.isBlank()) {
            return;
        }

        if ("answer_not_blank".equals(expression)) {
            Object answer = normalized.get("answer");
            if (!(answer instanceof String text) || text.isBlank()) {
                throw new ToolValidationException(
                        ExecutionEnums.ErrorCategory.QUALITY_FAIL,
                        "done_check_fail",
                        String.format("doneCheck表达式未通过: stepId=%s, expression=%s", step.getStepId(), expression)
                );
            }
            return;
        }

        Matcher matcher = ITEMS_COUNT_PATTERN.matcher(expression);
        if (matcher.matches()) {
            int minItems = Integer.parseInt(matcher.group(1));
            Object items = normalized.get("items");
            int size = items instanceof List<?> list ? list.size() : 0;
            if (size < minItems) {
                throw new ToolValidationException(
                        ExecutionEnums.ErrorCategory.QUALITY_FAIL,
                        "done_check_fail",
                        String.format("doneCheck表达式未通过: stepId=%s, expression=%s, actualItems=%d",
                                step.getStepId(), expression, size)
                );
            }
            return;
        }

        log.debug("[validator][output] 未识别doneCheck表达式，放行|stepId={} |expression={}", step.getStepId(), expression);
    }

    /**
     * 归一化只做结构解析，不自动纠错。
     */
    private Map<String, Object> normalizeOutput(WorkflowStep step, Object output) {
        if (output instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new HashMap<>();
            map.forEach((k, v) -> {
                if (k != null) {
                    normalized.put(String.valueOf(k), v);
                }
            });
            return normalized;
        }
        if (output instanceof List<?> list) {
            if (!expectsItemsCollection(step.getOutputSchema())) {
                throw new ToolValidationException(
                        ExecutionEnums.ErrorCategory.RETRYABLE_TOOL_ERROR,
                        "output_type_mismatch_stable",
                        String.format("输出类型不匹配: stepId=%s, capability=%s, actual=list",
                                step.getStepId(), step.getCapabilityName())
                );
            }
            return new HashMap<>(Map.of("items", list));
        }
        if (output instanceof String text) {
            if (expectsAnswerOnly(step.getOutputSchema())) {
                return new HashMap<>(Map.of("answer", text));
            }
            String trimmed = text.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    return OBJECT_MAPPER.readValue(trimmed, new TypeReference<Map<String, Object>>() {
                    });
                } catch (Exception parseError) {
                    throw new ToolValidationException(
                            ExecutionEnums.ErrorCategory.RETRYABLE_TOOL_ERROR,
                            "output_parse_error",
                            String.format("输出解析失败: stepId=%s, capability=%s, error=%s",
                                    step.getStepId(), step.getCapabilityName(), parseError.getMessage())
                    );
                }
            }
            if (trimmed.toLowerCase().startsWith("<!doctype") || trimmed.toLowerCase().startsWith("<html")) {
                throw new ToolValidationException(
                        ExecutionEnums.ErrorCategory.RETRYABLE_TOOL_ERROR,
                        "output_malformed_temporary",
                        String.format("输出疑似网关/限流页面: stepId=%s, capability=%s",
                                step.getStepId(), step.getCapabilityName())
                );
            }
            throw new ToolValidationException(
                    ExecutionEnums.ErrorCategory.RETRYABLE_TOOL_ERROR,
                    "output_type_mismatch_stable",
                    String.format("输出类型不匹配: stepId=%s, capability=%s, actual=string",
                            step.getStepId(), step.getCapabilityName())
            );
        }
        if (output == null) {
            return new HashMap<>();
        }
        return new HashMap<>(Map.of("value", output));
    }

    private void persistOutputSnapshot(WorkflowStep step, WorkflowContext context, Map<String, Object> normalized) {
        if (context == null || context.getRunId() == null || context.getRunId().isBlank()) {
            return;
        }
        try {
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("schemaVersion", firstNonBlank(
                    readAsString(normalized, "schemaVersion"),
                    readAsString(normalized, "schema_version"),
                    step.getSchemaVersion()
            ));
            snapshot.put("keys", normalized.keySet());
            snapshot.put("size", normalized.size());
            snapshot.put("masked", true);
            String json = OBJECT_MAPPER.writeValueAsString(maskSensitive(snapshot));
            stepClaimService.updateOutputSnapshot(context.getRunId(), step.getStepId(), json);
        } catch (Exception e) {
            log.warn("[validator][output] 输出快照写入失败|runId={} |stepId={} |error={}",
                    context.getRunId(), step.getStepId(), e.getMessage());
        }
    }

    private Map<String, Object> maskSensitive(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> masked = new HashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key != null && key.toLowerCase().matches(".*(token|secret|password|api[-_]?key).*")) {
                masked.put(key, "***");
            } else {
                masked.put(key, value);
            }
        }
        return masked;
    }

    private boolean isFieldPresent(String field, Map<String, Object> normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        if (!normalized.containsKey(field)) {
            return false;
        }
        return normalized.get(field) != null;
    }

    private boolean canCompatSkipMissingField(WorkflowStep step, String field) {
        if (properties.getMode() != SchemaValidationMode.COMPAT) {
            return false;
        }
        List<String> compatTools = properties.getCompatibilityTools();
        if (compatTools != null && !compatTools.isEmpty() && !compatTools.contains(step.getCapabilityName())) {
            return false;
        }
        Map<String, List<String>> optionalMap = properties.getOptionalOutputFieldsByTool();
        if (optionalMap == null || optionalMap.isEmpty()) {
            return false;
        }
        List<String> optionalFields = optionalMap.get(step.getCapabilityName());
        return optionalFields != null && optionalFields.contains(field);
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
            warnings = new ArrayList<>();
            context.putAttribute("workflow.quality.warnings", warnings);
        }
        warnings.add(warning);
        context.putAttribute("workflow.quality.warning.count", warnings.size());
    }

    private void recordValidationContext(WorkflowContext context) {
        if (context == null) {
            return;
        }
        Object rawCount = context.getAttributes().get("workflow.validation.output.checked.count");
        int count = rawCount instanceof Number number ? number.intValue() : 0;
        context.putAttribute("workflow.validation.output.checked.count", count + 1);
    }

    private boolean expectsItemsCollection(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return false;
        }
        Object required = schema.get("required");
        if (!(required instanceof List<?> requiredFields)) {
            return false;
        }
        return requiredFields.stream().map(String::valueOf).anyMatch("items"::equals);
    }

    private boolean expectsAnswerOnly(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return true;
        }
        Object required = schema.get("required");
        if (!(required instanceof List<?> requiredFields) || requiredFields.isEmpty()) {
            return true;
        }
        return requiredFields.size() == 1 && "answer".equals(String.valueOf(requiredFields.get(0)));
    }

    private int countNormalizedEvidenceSources(WorkflowContext context) {
        if (context == null || context.getEvidence() == null || context.getEvidence().isEmpty()) {
            if (context != null) {
                context.putAttribute("workflow.evidence.standard", "article_id_dedup_with_snippet_required");
                context.putAttribute("workflow.evidence.raw.count", 0);
                context.putAttribute("workflow.evidence.normalized.count", 0);
            }
            return 0;
        }
        Set<String> sources = new HashSet<>();
        for (RetrievalResult result : context.getEvidence()) {
            if (result == null || result.getArticleId() == null) {
                continue;
            }
            String snippet = firstNonBlank(result.getMatchedSnippet(), result.getFullContent());
            if (snippet == null || snippet.isBlank()) {
                continue;
            }
            sources.add("article:" + result.getArticleId());
        }
        context.putAttribute("workflow.evidence.standard", "article_id_dedup_with_snippet_required");
        context.putAttribute("workflow.evidence.raw.count", context.getEvidence().size());
        context.putAttribute("workflow.evidence.normalized.count", sources.size());
        return sources.size();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String readContextAsString(WorkflowContext context, String key) {
        if (context == null || context.getAttributes() == null || key == null) {
            return null;
        }
        Object value = context.getAttributes().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String readAsString(Map<String, Object> map, String key) {
        if (map == null || key == null || !map.containsKey(key) || map.get(key) == null) {
            return null;
        }
        return String.valueOf(map.get(key));
    }
}
