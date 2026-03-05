package com.example.news.aggregation.agent.workflow.selector;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * Deterministic tool selection output for traceability and replay.
 */
@Value
@Builder
public class ToolSelectionResult {

    String selectedTool;
    String reasonCode;
    List<String> candidates;
    Map<String, String> circuitStateByTool;
    Map<String, ToolHealthSnapshot> healthSnapshotByTool;

    public boolean hasSelectedTool() {
        return selectedTool != null && !selectedTool.isBlank();
    }
}

