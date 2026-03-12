package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.state.PlannerState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务分解节点。
 * 调用 LLM（Structured Output）将用户查询动态分解为子任务列表。
 * LLM 调用失败或输出不合法时，降级到规则模板保证系统可用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskDecompositionNode {

    private static final String TOOL_DESCRIPTIONS =
            "- search_news：ES 关键词检索。适合精确匹配：人名/机构名/产品名/地点、明确事件词、明确时间范围过滤；优势是精确命中和可解释性。\n" +
            "- retrieve_news：向量语义检索。适合抽象问法、同义改写、概念描述、跨表述匹配；优势是语义召回，弱项是精确词过滤。\n" +
            "- hybrid_retrieve_news：混合检索（向量+关键词+RRF融合）。适合新闻问答默认检索、复杂多实体问题、既要语义覆盖又要精确命中的场景，通常优先级高于单一路径。\n" +
            "- rerank_results：MMR 重排。对检索结果去重和提升多样性，必须在检索步骤之后使用。\n" +
            "- llm_generate：基于已有证据生成最终答案，必须是最后一步且依赖前序检索结果。";

    private static final String PLANNING_CONSTRAINTS =
            "1. llm_generate 必须是最后一步，且必须依赖至少一个检索步骤。\n" +
            "2. rerank_results 只能在检索步骤之后使用。\n" +
            "3. 无依赖关系的步骤可以设置 parallelizable=true 以并行执行。\n" +
            "4. 步骤数量控制在 2~6 步，不要过度拆分。\n" +
            "5. requiredTools 中的工具名必须来自上方可用工具列表。\n" +
            "6. 如果查询涉及多个实体（如\"中国和美国\"），必须为每个实体生成独立的检索任务，然后合并结果。\n" +
            "7. 多实体查询的检索策略：每个实体单独检索 -> 合并去重 -> 生成答案。\n" +
            "8. 新闻问答默认优先使用 hybrid_retrieve_news；若问题包含精确实体或时间过滤，可补充 search_news 并在后续合并。\n" +
            "9. 当查询语义模糊、概念化、同义改写明显时，不要只用 search_news，至少包含一次 retrieve_news 或 hybrid_retrieve_news。\n" +
            "10. 当查询同时包含“精确实体约束 + 语义背景问题”时，优先规划为“search_news + hybrid_retrieve_news”双检索路径。";

    private final ChatClient chatClient;

    public PlannerState execute(PlannerState state) {
        state.incrementStep();

        try {
            String prompt = buildPrompt(state);
            log.info("[task-decompose] 调用 LLM 分解任务|isReplan={}|query={}",
                    state.isReplan(), truncate(state.getQuery(), 100));

            DecompositionResult result = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(DecompositionResult.class);

            if (result == null || result.getTasks() == null || result.getTasks().isEmpty()) {
                log.warn("[task-decompose] LLM 输出为空，降级到规则模板|query={}", truncate(state.getQuery(), 100));
                return fallbackToRuleTemplate(state);
            }

            List<PlannerState.SubTask> subTasks = result.toSubTasks();
            if (subTasks.isEmpty()) {
                log.warn("[task-decompose] LLM 输出转换后为空列表，降级到规则模板");
                return fallbackToRuleTemplate(state);
            }

            log.info("[task-decompose] LLM 分解成功|stepCount={}|isReplan={}", subTasks.size(), state.isReplan());
            state.setSubTasks(subTasks);
            return state;

        } catch (Exception e) {
            log.error("[task-decompose] LLM 调用异常，降级到规则模板|error={}", e.getMessage(), e);
            return fallbackToRuleTemplate(state);
        }
    }

    // ── Prompt 构建 ──────────────────────────────────────────────────────────

    private String buildPrompt(PlannerState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个新闻检索规划器。请将以下查询分解为可执行的子任务列表。\n\n");
        sb.append("【查询】").append(state.getQuery()).append("\n");
        sb.append("【任务类型】").append(resolveTaskFamily(state)).append("\n");

        // 传递实体信息
        List<String> entities = extractEntities(state);
        if (entities != null && !entities.isEmpty()) {
            sb.append("【提取的实体】").append(String.join("、", entities)).append("\n");
            sb.append("【重要】该查询涉及多个实体，请为每个实体生成独立的检索任务！\n");
        }
        sb.append("\n");

        sb.append("【可用工具及能力说明】\n").append(TOOL_DESCRIPTIONS).append("\n\n");
        sb.append("【规划约束】\n").append(PLANNING_CONSTRAINTS).append("\n");

        if (state.isReplan()) {
            sb.append("\n【上次执行失败，请据此修正计划】\n");
            sb.append("失败原因：").append(state.getReplanReason()).append("\n");
            sb.append("已完成步骤执行情况：\n");
            appendStepResults(sb, state.getStepResults());
            sb.append("请根据失败原因调整计划，避免重复使用导致失败的工具或路径。\n");
        }

        sb.append("\n【输出格式】\n");
        sb.append("输出合法 JSON，结构为：\n");
        sb.append("{\n");
        sb.append("  \"tasks\": [\n");
        sb.append("    {\n");
        sb.append("      \"id\": \"task-1\",\n");
        sb.append("      \"type\": \"SEARCH\",\n");
        sb.append("      \"description\": \"...\",\n");
        sb.append("      \"dependencies\": [],\n");
        sb.append("      \"requiredTools\": [\"search_news\"],\n");
        sb.append("      \"parameters\": { \"query\": \"...\", \"filters\": {} },\n");
        sb.append("      \"parallelizable\": true\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n");
        sb.append("只输出 JSON，不要有任何其他文字。");

        return sb.toString();
    }

    private void appendStepResults(StringBuilder sb, Map<String, PlannerState.StepExecutionResult> stepResults) {
        if (stepResults == null || stepResults.isEmpty()) {
            sb.append("  （无已完成步骤记录）\n");
            return;
        }
        stepResults.forEach((stepId, result) -> {
            sb.append("  - ").append(stepId).append(": ")
              .append(result.getStatus());
            if (result.getToolUsed() != null) {
                sb.append(", 工具=").append(result.getToolUsed());
            }
            if (result.getEvidenceCount() > 0) {
                sb.append(", 证据数=").append(result.getEvidenceCount());
            }
            if ("FAILED".equals(result.getStatus()) && result.getFailureReason() != null) {
                sb.append(", 失败原因=").append(result.getFailureReason());
            }
            sb.append("\n");
        });
    }

    private String resolveTaskFamily(PlannerState state) {
        if (state.getRouterResult() != null && state.getRouterResult().getTaskFamily() != null) {
            return state.getRouterResult().getTaskFamily();
        }
        return "QA";
    }

    /**
     * 从 RouterResult 中提取实体列表
     */
    private List<String> extractEntities(PlannerState state) {
        if (state.getRouterResult() != null && state.getRouterResult().getEntities() != null) {
            return state.getRouterResult().getEntities();
        }
        return null;
    }

    // ── 降级规则模板 ─────────────────────────────────────────────────────────

    /**
     * LLM 调用失败时的保底降级逻辑（保留原规则树作为备用）。
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
                    .description("关键词检索相关资料")
                    .parameters(buildParams(query, filters))
                    .build());
            subTasks.add(PlannerState.SubTask.builder()
                    .id("task-2")
                    .type("RETRIEVE")
                    .description("向量检索补充证据")
                    .parameters(buildParams(query, filters))
                    .build());
            subTasks.add(PlannerState.SubTask.builder()
                    .id("task-3")
                    .type("COMPARE".equals(taskFamily) ? "COMPARE"
                            : "TIMELINE".equals(taskFamily) ? "TIMELINE" : "ANALYZE")
                    .description("基于证据进行综合分析")
                    .dependencies(List.of("task-1", "task-2"))
                    .parameters(buildGenerateParams(taskFamily))
                    .build());
        } else {
            subTasks.add(PlannerState.SubTask.builder()
                    .id("task-1")
                    .type("SEARCH")
                    .description("检索相关资料")
                    .parameters(buildParams(query, filters))
                    .build());
            subTasks.add(PlannerState.SubTask.builder()
                    .id("task-2")
                    .type("ANALYZE")
                    .description("基于证据生成答案")
                    .dependencies(List.of("task-1"))
                    .parameters(buildGenerateParams(taskFamily))
                    .build());
        }

        log.info("[task-decompose] 规则模板降级完成|taskFamily={}|stepCount={}", taskFamily, subTasks.size());
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

    private static String truncate(String value, int maxLength) {
        if (value == null || maxLength <= 0) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
