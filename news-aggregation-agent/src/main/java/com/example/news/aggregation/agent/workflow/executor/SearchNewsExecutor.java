package com.example.news.aggregation.agent.workflow.executor;

import com.example.news.aggregation.agent.client.RetrievalClient;
import com.example.news.aggregation.agent.tool.dto.RetrievalResult;
import com.example.news.aggregation.agent.workflow.CapabilityExecutor;
import com.example.news.aggregation.agent.workflow.CapabilityMetadata;
import com.example.news.aggregation.agent.workflow.WorkflowContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 关键词检索能力。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchNewsExecutor implements CapabilityExecutor {

    private final RetrievalClient retrievalClient;

    @Override
    public String capabilityName() {
        return "search_news";
    }

    @Override
    public CapabilityMetadata metadata() {
        return CapabilityMetadata.builder()
                .name("search_news")
                .version("v1")
                .description("关键词检索")
                .timeoutMs(3000L)
                .costLevel("LOW")
                .permissionScope("PUBLIC")
                .build();
    }

    @Override
    public Object execute(Map<String, Object> parameters, WorkflowContext context) {
        String query = parameters != null && parameters.get("query") != null
                ? String.valueOf(parameters.get("query"))
                : context.getQuery();
        int topK = parameters != null && parameters.get("topK") instanceof Number
                ? ((Number) parameters.get("topK")).intValue()
                : 10;

        Map<String, Object> filters = extractFilters(parameters, context);
        String sessionId = context != null ? context.getSessionId() : "unknown";
        log.info("[链路最终] 开始关键词检索FLOW|agent|node=search_news|step=start|sessionId={}|topK={}|query={}|reason=任务规划/默认检索|next=检索服务", sessionId, topK, truncate(query, 200));
        List<RetrievalResult> results = retrievalClient.keywordSearch(query, topK, filters);
        context.addEvidence(results);
        log.info("[链路最终] 关键词检索完成FLOW|agent|node=search_news|step=end|sessionId={}|resultCount={}|next=证据汇总/后续节点",
                sessionId, results.size());
        return results;
    }

    /**
     * 提取通用过滤条件。
     */
    private Map<String, Object> extractFilters(Map<String, Object> parameters, WorkflowContext context) {
        if (parameters == null || parameters.isEmpty()) {
            return extractFiltersFromContext(context);
        }
        Object filtersObj = parameters.get("filters");
        if (filtersObj instanceof Map<?, ?> map) {
            Map<String, Object> filters = new java.util.HashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    filters.put(String.valueOf(key), value);
                }
            });
            return filters;
        }

        // 允许参数直接透传过滤条件
        String[] keys = new String[] {
                "timeRange", "startDate", "endDate",
                "keywords", "topic", "category",
                "language", "region", "source",
                "publisher", "sortBy"
        };
        Map<String, Object> filters = new java.util.HashMap<>();
        for (String key : keys) {
            if (parameters.containsKey(key)) {
                filters.put(key, parameters.get(key));
            }
        }
        if (!filters.isEmpty()) {
            return filters;
        }
        return extractFiltersFromContext(context);
    }

    private Map<String, Object> extractFiltersFromContext(WorkflowContext context) {
        if (context == null || context.getAttributes() == null) {
            return null;
        }
        Object value = context.getAttributes().get("filters");
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> filters = new java.util.HashMap<>();
            map.forEach((key, item) -> {
                if (key != null) {
                    filters.put(String.valueOf(key), item);
                }
            });
            return filters.isEmpty() ? null : filters;
        }
        return null;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
