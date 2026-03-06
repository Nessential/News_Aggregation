package com.example.news.aggregation.agent.workflow.executor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一工具输出结构，避免 list/object 输出漂移导致 schema 校验误判。
 */
public final class ToolOutputEnvelope {

    private ToolOutputEnvelope() {
    }

    public static Map<String, Object> items(String capability,
                                            List<?> items,
                                            String schemaVersion) {
        List<?> safeItems = items == null ? List.of() : items;
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("capability", capability);
        envelope.put("items", safeItems);
        envelope.put("count", safeItems.size());
        envelope.put("schemaVersion", schemaVersion == null ? "execution-plan/1.0" : schemaVersion);
        return envelope;
    }
}

