package com.example.news.aggregation.agent.workflow.selector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolHealthWindowTest {

    @Test
    void shouldCountFailureTypesAndRatesInSlidingWindow() {
        ToolSelectorProperties properties = new ToolSelectorProperties();
        properties.setHealthWindowSize(5);
        ToolHealthWindow window = new ToolHealthWindow(properties);

        window.recordSuccess("search_news", "search_news", 10);
        window.recordFailure("search_news", "search_news", ToolFailureCategory.INFRA_FAIL, 20);
        window.recordFailure("search_news", "search_news", ToolFailureCategory.TIMEOUT, 30);
        window.recordFailure("search_news", "search_news", ToolFailureCategory.SCHEMA_FAIL, 40);
        window.recordFailure("search_news", "search_news", ToolFailureCategory.QUALITY_FAIL, 50);

        ToolHealthSnapshot snapshot = window.snapshot("search_news", "search_news");
        assertEquals(5, snapshot.getSampleCount());
        assertEquals(1, snapshot.getSuccessCount());
        assertEquals(1, snapshot.getInfraFailCount());
        assertEquals(1, snapshot.getTimeoutCount());
        assertEquals(1, snapshot.getSchemaFailCount());
        assertEquals(1, snapshot.getQualityFailCount());
        assertEquals(0.2, snapshot.getSuccessRate());
        assertEquals(0.2, snapshot.getInfraFailRate());
        assertEquals(0.2, snapshot.getTimeoutRate());
        assertEquals(0.2, snapshot.getSchemaFailRate());
        assertEquals(0.2, snapshot.getQualityFailRate());
        assertEquals(30, snapshot.getAvgLatencyMs());
        assertEquals(50, snapshot.getMaxLatencyMs());
    }

    @Test
    void shouldEvictOldEventsWhenWindowIsFull() {
        ToolSelectorProperties properties = new ToolSelectorProperties();
        properties.setHealthWindowSize(3);
        ToolHealthWindow window = new ToolHealthWindow(properties);

        window.recordSuccess("search_news", "search_news", 10);
        window.recordFailure("search_news", "search_news", ToolFailureCategory.INFRA_FAIL, 20);
        window.recordFailure("search_news", "search_news", ToolFailureCategory.TIMEOUT, 30);
        window.recordFailure("search_news", "search_news", ToolFailureCategory.SCHEMA_FAIL, 40);

        ToolHealthSnapshot snapshot = window.snapshot("search_news", "search_news");
        assertEquals(3, snapshot.getSampleCount());
        assertEquals(0, snapshot.getSuccessCount());
        assertEquals(1, snapshot.getInfraFailCount());
        assertEquals(1, snapshot.getTimeoutCount());
        assertEquals(1, snapshot.getSchemaFailCount());
        assertEquals(0, snapshot.getQualityFailCount());
        assertEquals(30, snapshot.getAvgLatencyMs());
        assertEquals(40, snapshot.getMaxLatencyMs());
    }
}
