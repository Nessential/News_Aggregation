package com.example.news.aggregation.agent.workflow.selector;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool selector/circuit/health configuration.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.agent.tool-selector")
public class ToolSelectorProperties {

    private boolean enabled = true;
    private int healthWindowSize = 20;
    private int healthMinSamples = 5;
    private double primaryInfraFailRateThreshold = 0.6;
    private int primaryInfraFailCountThreshold = 3;
    private int circuitOpenSeconds = 30;
    private int halfOpenOwnerLeaseSeconds = 10;
    private int casMaxRetries = 3;
    private Map<String, List<String>> capabilityFallbacks = new HashMap<>();
}
