package com.example.news.aggregation.llm.springai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Planner 资源估算配置。
 * 通过配置驱动 taskType -> 预计耗时 / 所需工具，避免硬编码膨胀。
 */
@Data
@ConfigurationProperties(prefix = "app.llm.planner.resource-estimation")
public class PlannerResourceEstimationProperties {

    /**
     * taskType -> 预估执行时长（秒）
     */
    private Map<String, Integer> typeTimeMap = defaultTypeTimeMap();

    /**
     * taskType -> 候选工具列表
     */
    private Map<String, List<String>> typeToolsMap = defaultTypeToolsMap();

    private static Map<String, Integer> defaultTypeTimeMap() {
        Map<String, Integer> defaults = new LinkedHashMap<>();
        defaults.put("SEARCH", 2);
        defaults.put("RETRIEVE", 3);
        defaults.put("SUMMARIZE", 5);
        defaults.put("COMPARE", 7);
        defaults.put("ANALYZE", 10);
        defaults.put("TIMELINE", 8);
        defaults.put("DEEP_DIVE", 10);
        return defaults;
    }

    private static Map<String, List<String>> defaultTypeToolsMap() {
        Map<String, List<String>> defaults = new LinkedHashMap<>();
        defaults.put("SEARCH", List.of("search_news"));
        defaults.put("RETRIEVE", List.of("retrieve_news", "rerank_results"));
        defaults.put("SUMMARIZE", List.of("llm_generate"));
        defaults.put("COMPARE", List.of("llm_generate"));
        defaults.put("ANALYZE", List.of("llm_generate"));
        defaults.put("TIMELINE", List.of("llm_generate"));
        defaults.put("DEEP_DIVE", List.of("llm_generate"));
        return defaults;
    }
}

