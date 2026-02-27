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

@Slf4j
@Component
@RequiredArgsConstructor
public class RetrieveNewsExecutor implements CapabilityExecutor {

    private final RetrievalClient retrievalClient;

    @Override
    public String capabilityName() {
        return "retrieve_news";
    }

    @Override
    public CapabilityMetadata metadata() {
        return CapabilityMetadata.builder()
                .name("retrieve_news")
                .version("v1")
                .description("Retrieve evidence from news store")
                .timeoutMs(5000L)
                .costLevel("MEDIUM")
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
        double minScore = parameters != null && parameters.get("minScore") instanceof Number
                ? ((Number) parameters.get("minScore")).doubleValue()
                : 0.5;
        String mode = parameters != null && parameters.get("mode") != null
                ? String.valueOf(parameters.get("mode")).toUpperCase()
                : "HYBRID";
        Map<String, Object> filters = extractFilters(parameters, context);
        String sessionId = context != null ? context.getSessionId() : "unknown";
        log.info("[FLOW][retrieve-news] start|sessionId={} |mode={} |topK={} |minScore={} |query={} |filters={} |next=retrieval-client",
                sessionId, mode, topK, minScore, truncate(query, 200), summarizeFilters(filters));

        List<RetrievalResult> results;
        if ("VECTOR".equals(mode)) {
            results = retrievalClient.vectorSearch(query, topK, minScore, filters);
        } else {
            results = retrievalClient.hybridSearch(query, topK, minScore, filters);
        }

        context.addEvidence(results);
        log.info("[FLOW][retrieve-news] end|sessionId={} |mode={} |resultCount={} |next=evidence-merge",
                sessionId, mode, results.size());
        return results;
    }

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

    private String summarizeFilters(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return "{}";
        }
        return filters.toString();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
