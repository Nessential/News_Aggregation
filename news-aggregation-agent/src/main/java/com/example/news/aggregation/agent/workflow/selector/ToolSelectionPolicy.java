package com.example.news.aggregation.agent.workflow.selector;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Candidate list policy with deterministic ordering.
 */
@Component
@RequiredArgsConstructor
public class ToolSelectionPolicy {

    private final ToolSelectorProperties properties;

    public List<String> buildCandidates(String capability, String primaryTool, List<String> fallbackTools) {
        Set<String> ordered = new LinkedHashSet<>();
        if (primaryTool != null && !primaryTool.isBlank()) {
            ordered.add(primaryTool.trim());
        }
        if (fallbackTools != null) {
            for (String fallbackTool : fallbackTools) {
                if (fallbackTool != null && !fallbackTool.isBlank()) {
                    ordered.add(fallbackTool.trim());
                }
            }
        }
        if (capability != null
                && properties.getCapabilityFallbacks() != null
                && properties.getCapabilityFallbacks().containsKey(capability)) {
            List<String> configured = properties.getCapabilityFallbacks().get(capability);
            if (configured != null) {
                for (String tool : configured) {
                    if (tool != null && !tool.isBlank()) {
                        ordered.add(tool.trim());
                    }
                }
            }
        }
        return new ArrayList<>(ordered);
    }
}

