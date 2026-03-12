package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.planner.PlannerToolSchemaProvider;
import com.example.news.aggregation.llm.springai.prompt.PromptRegistry;
import com.example.news.aggregation.llm.springai.state.PlannerState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * 任务分解节点。
 * <p>
 * 当前主链路通过 Spring AI 的工具声明能力，将检索与重排工具的结构化定义注入给模型，
 * 让模型基于正式工具 schema 生成任务计划。
 * 如果模型规划失败，仍然会回退到规则模板，保证系统可用性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskDecompositionNode {

    private final ChatClient.Builder chatClientBuilder;
    private final PromptRegistry promptRegistry;
    private final PlannerToolSchemaProvider plannerToolSchemaProvider;

    public PlannerState execute(PlannerState state) {
        state.incrementStep();

        try {
            String systemPrompt = promptRegistry.getPrompt("planner-tool", "");
            String userPrompt = buildUserPrompt(state);
            List<String> registeredToolNames = resolveRegisteredToolNames();
            log.info("[任务规划] 本次已向大模型注册工具声明。toolCount={}，tools={}",
                    registeredToolNames.size(), registeredToolNames);
            log.info("[任务规划] 开始执行基于工具声明的任务规划。query={}", truncate(state.getQuery(), 120));

            DecompositionResult result = chatClientBuilder.build()
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .tools(plannerToolSchemaProvider)
                    .call()
                    .entity(DecompositionResult.class);

            if (result == null || result.getTasks() == null || result.getTasks().isEmpty()) {
                log.warn("[任务规划] 模型未返回有效任务，使用规则模板兜底。query={}", truncate(state.getQuery(), 120));
                return fallbackToRuleTemplate(state);
            }

            log.info("[任务规划] 模型原始任务计划={}", result);

            List<PlannerState.SubTask> subTasks = normalizeSubTasks(result.toSubTasks(), state);
            subTasks = ensureAnswerGenerationStep(subTasks, state);
            if (subTasks.isEmpty()) {
                log.warn("[任务规划] 模型返回的任务列表为空，使用规则模板兜底。query={}", truncate(state.getQuery(), 120));
                return fallbackToRuleTemplate(state);
            }

            log.info("[任务规划] 任务规划成功，生成子任务数量={}，query={}",
                    subTasks.size(), truncate(state.getQuery(), 120));
            log.info("[任务规划] 规范化后的子任务列表={}", subTasks);
            state.setSubTasks(subTasks);
            return state;
        } catch (Exception ex) {
            log.error("[任务规划] 任务规划失败，使用规则模板兜底。query={}, error={}",
                    truncate(state.getQuery(), 120), ex.getMessage(), ex);
            return fallbackToRuleTemplate(state);
        }
    }

    private String buildUserPrompt(PlannerState state) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户问题：").append(nullToEmpty(state.getQuery())).append('\n');
        builder.append("任务类型：").append(resolveTaskFamily(state)).append('\n');

        List<String> entities = extractEntities(state);
        if (!entities.isEmpty()) {
            builder.append("识别出的实体：").append(String.join("、", entities)).append('\n');
            builder.append("请为每个实体单独规划检索步骤，并在最终步骤汇总生成答案。").append('\n');
        }

        Map<String, Object> filters = buildFilters(state);
        if (!filters.isEmpty()) {
            builder.append("可用过滤条件：").append(filters).append('\n');
        }

        if (state.isReplan()) {
            builder.append("这是一次重新规划。").append('\n');
            builder.append("重新规划原因：").append(nullToEmpty(state.getReplanReason())).append('\n');
            builder.append("已完成步骤摘要：").append('\n');
            appendStepResults(builder, state.getStepResults());
        }

        builder.append("请严格输出任务计划 JSON。");
        return builder.toString();
    }

    private void appendStepResults(StringBuilder builder, Map<String, PlannerState.StepExecutionResult> stepResults) {
        if (stepResults == null || stepResults.isEmpty()) {
            builder.append("无已完成步骤。").append('\n');
            return;
        }
        stepResults.forEach((stepId, result) -> {
            builder.append("- 步骤ID=").append(stepId)
                    .append("，状态=").append(result.getStatus());
            if (result.getToolUsed() != null) {
                builder.append("，工具=").append(result.getToolUsed());
            }
            if (result.getEvidenceCount() > 0) {
                builder.append("，证据数量=").append(result.getEvidenceCount());
            }
            if (result.getFailureReason() != null && !result.getFailureReason().isBlank()) {
                builder.append("，失败原因=").append(result.getFailureReason());
            }
            builder.append('\n');
        });
    }

    private List<PlannerState.SubTask> ensureAnswerGenerationStep(List<PlannerState.SubTask> input, PlannerState state) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }

        List<PlannerState.SubTask> subTasks = new ArrayList<>(input);
        boolean hasGenerateStep = subTasks.stream().anyMatch(this::isAnswerGenerationStep);
        if (hasGenerateStep) {
            return subTasks;
        }

        List<String> dependencyIds = subTasks.stream()
                .map(PlannerState.SubTask::getId)
                .filter(id -> id != null && !id.isBlank())
                .toList();

        PlannerState.SubTask generateStep = PlannerState.SubTask.builder()
                .id("task-" + (subTasks.size() + 1))
                .type(resolveGenerateType(state))
                .description("基于前置检索结果生成最终答案")
                .dependencies(dependencyIds)
                .requiredTools(List.of("llm_generate"))
                .parameters(Map.of("taskFamily", resolveTaskFamily(state)))
                .build();

        subTasks.add(generateStep);
        log.info("[任务规划] 模型未显式生成答案步骤，系统已自动补齐。stepId={}", generateStep.getId());
        return subTasks;
    }

    /**
     * 对模型生成的子任务做轻量规范化。
     * 当前主要处理时间范围参数，避免 recent 这类模糊值直接进入执行层。
     */
    private List<PlannerState.SubTask> normalizeSubTasks(List<PlannerState.SubTask> input, PlannerState state) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        List<PlannerState.SubTask> normalized = new ArrayList<>();
        for (PlannerState.SubTask task : input) {
            if (task == null) {
                continue;
            }
            Map<String, Object> parameters = normalizeParameters(task.getParameters(), state);
            task.setParameters(parameters);
            normalized.add(task);
        }
        return normalized;
    }

    private Map<String, Object> normalizeParameters(Map<String, Object> parameters, PlannerState state) {
        if (parameters == null || parameters.isEmpty()) {
            return parameters;
        }
        Map<String, Object> normalized = new HashMap<>(parameters);
        Object filtersObject = normalized.get("filters");
        if (filtersObject instanceof Map<?, ?> rawFilters) {
            Map<String, Object> filters = new HashMap<>();
            rawFilters.forEach((key, value) -> {
                if (key != null) {
                    filters.put(String.valueOf(key), value);
                }
            });
            normalizeTimeRange(filters, state);
            normalized.put("filters", filters);
        }
        return normalized;
    }

    private void normalizeTimeRange(Map<String, Object> filters, PlannerState state) {
        if (filters == null || filters.isEmpty()) {
            return;
        }
        Object timeRange = filters.get("timeRange");
        if (timeRange == null) {
            return;
        }
        String raw = String.valueOf(timeRange).trim();
        if (raw.isBlank()) {
            return;
        }
        String normalized = mapRelativeTimeRange(raw);
        if (!raw.equals(normalized)) {
            filters.put("timeRange", normalized);
            log.info("[任务规划] 已将模糊时间范围规范化。原值={}，规范值={}，query={}",
                    raw, normalized, truncate(state.getQuery(), 120));
        }
    }

    /**
     * 相对时间范围的终点统一理解为当前时间。
     * 这里输出标准窗口值，供执行层按“当前时间向前倒推”解释。
     */
    private String mapRelativeTimeRange(String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "recent", "latest", "recently", "近期", "最近", "最新", "最近几天" -> "7d";
            case "近一周", "最近一周", "7天", "7d" -> "7d";
            case "近两周", "最近两周", "14天", "14d" -> "14d";
            case "近一个月", "最近一个月", "近30天", "30天", "30d", "本月" -> "30d";
            case "近三个月", "最近三个月", "90天", "90d" -> "90d";
            default -> raw;
        };
    }

    private boolean isAnswerGenerationStep(PlannerState.SubTask task) {
        if (task == null) {
            return false;
        }
        if (task.getRequiredTools() != null && task.getRequiredTools().contains("llm_generate")) {
            return true;
        }
        String type = task.getType();
        return "QA".equalsIgnoreCase(type)
                || "ANALYZE".equalsIgnoreCase(type)
                || "SUMMARY".equalsIgnoreCase(type)
                || "COMPARE".equalsIgnoreCase(type)
                || "TIMELINE".equalsIgnoreCase(type)
                || "DEEP_DIVE".equalsIgnoreCase(type);
    }

    private String resolveGenerateType(PlannerState state) {
        String taskFamily = resolveTaskFamily(state);
        if (taskFamily == null || taskFamily.isBlank()) {
            return "ANALYZE";
        }
        return taskFamily;
    }

    private String resolveTaskFamily(PlannerState state) {
        if (state.getRouterResult() != null && state.getRouterResult().getTaskFamily() != null) {
            return state.getRouterResult().getTaskFamily();
        }
        return "QA";
    }

    private List<String> extractEntities(PlannerState state) {
        if (state.getRouterResult() == null || state.getRouterResult().getEntities() == null) {
            return List.of();
        }
        LinkedHashSet<String> entities = new LinkedHashSet<>();
        for (String entity : state.getRouterResult().getEntities()) {
            if (entity != null && !entity.isBlank()) {
                entities.add(entity.trim());
            }
        }
        return new ArrayList<>(entities);
    }

    /**
     * 当模型规划失败时，使用规则模板生成最小可执行计划。
     */
    private PlannerState fallbackToRuleTemplate(PlannerState state) {
        String query = state.getQuery();
        String taskFamily = resolveTaskFamily(state);
        Map<String, Object> filters = buildFilters(state);

        List<PlannerState.SubTask> subTasks = new ArrayList<>();

        if ("COMPARE".equals(taskFamily) || "TIMELINE".equals(taskFamily) || "DEEP_DIVE".equals(taskFamily)) {
            subTasks.add(PlannerState.SubTask.builder()
                    .id("task-1")
                    .type("SEARCH")
                    .description("检索相关新闻")
                    .requiredTools(List.of("search_news"))
                    .parameters(buildParams(query, filters))
                    .build());
            subTasks.add(PlannerState.SubTask.builder()
                    .id("task-2")
                    .type("RETRIEVE")
                    .description("补充语义检索证据")
                    .requiredTools(List.of("hybrid_retrieve_news"))
                    .parameters(buildHybridParams(query, filters))
                    .build());
            subTasks.add(PlannerState.SubTask.builder()
                    .id("task-3")
                    .type(resolveGenerateType(state))
                    .description("基于证据生成最终答案")
                    .dependencies(List.of("task-1", "task-2"))
                    .requiredTools(List.of("llm_generate"))
                    .parameters(buildGenerateParams(taskFamily))
                    .build());
        } else {
            subTasks.add(PlannerState.SubTask.builder()
                    .id("task-1")
                    .type("RETRIEVE")
                    .description("混合检索相关新闻")
                    .requiredTools(List.of("hybrid_retrieve_news"))
                    .parameters(buildHybridParams(query, filters))
                    .build());
            subTasks.add(PlannerState.SubTask.builder()
                    .id("task-2")
                    .type(resolveGenerateType(state))
                    .description("基于证据生成最终答案")
                    .dependencies(List.of("task-1"))
                    .requiredTools(List.of("llm_generate"))
                    .parameters(buildGenerateParams(taskFamily))
                    .build());
        }

        log.info("[任务规划] 规则模板兜底完成，生成子任务数量={}。", subTasks.size());
        state.setSubTasks(subTasks);
        return state;
    }

    private Map<String, Object> buildParams(String query, Map<String, Object> filters) {
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        if (filters != null && !filters.isEmpty()) {
            params.put("filters", filters);
        }
        return params;
    }

    private Map<String, Object> buildHybridParams(String query, Map<String, Object> filters) {
        Map<String, Object> params = buildParams(query, filters);
        params.put("mode", "HYBRID");
        return params;
    }

    private Map<String, Object> buildGenerateParams(String taskFamily) {
        Map<String, Object> params = new HashMap<>();
        params.put("taskFamily", taskFamily);
        return params;
    }

    private Map<String, Object> buildFilters(PlannerState state) {
        Map<String, Object> filters = new HashMap<>();
        if (state.getRouterResult() != null && state.getRouterResult().getParams() != null) {
            Map<String, Object> params = state.getRouterResult().getParams();
            putIfPresent(filters, "timeRange", params, "timeRange", "time_range", "time-range");
            putIfPresent(filters, "startDate", params, "startDate", "start_date");
            putIfPresent(filters, "endDate", params, "endDate", "end_date");
            putIfPresent(filters, "keywords", params, "keywords");
            putIfPresent(filters, "topic", params, "topic");
            putIfPresent(filters, "category", params, "category");
            putIfPresent(filters, "language", params, "language", "lang");
            putIfPresent(filters, "region", params, "region");
            putIfPresent(filters, "source", params, "source");
            putIfPresent(filters, "publisher", params, "publisher");
            putIfPresent(filters, "sortBy", params, "sortBy", "sort_by");
        }
        if (state.getContext() != null) {
            Object contextFilters = state.getContext().get("filters");
            if (contextFilters instanceof Map<?, ?> map) {
                map.forEach((key, value) -> {
                    if (key != null && value != null) {
                        filters.put(String.valueOf(key), value);
                    }
                });
            }
        }
        return filters;
    }

    private void putIfPresent(Map<String, Object> target, String normalizedKey,
                              Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) {
                Object value = source.get(key);
                if (value != null) {
                    target.put(normalizedKey, value);
                    return;
                }
            }
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private List<String> resolveRegisteredToolNames() {
        List<String> toolNames = new ArrayList<>();
        Method[] methods = plannerToolSchemaProvider.getClass().getMethods();
        for (Method method : methods) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool == null) {
                continue;
            }
            String toolName = tool.name();
            if (toolName == null || toolName.isBlank()) {
                toolName = method.getName();
            }
            toolNames.add(toolName);
        }
        return toolNames;
    }
}
