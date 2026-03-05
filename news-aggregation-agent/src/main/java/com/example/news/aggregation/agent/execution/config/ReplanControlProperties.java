package com.example.news.aggregation.agent.execution.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Week5 重规划控制参数。
 * 规则：先读 capability 级配置，缺省时回落全局默认值。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.agent.replan")
public class ReplanControlProperties {

    /** run 级最大重规划次数 */
    private int maxReplansPerRun = 2;
    /** step 级最大重规划次数 */
    private int maxReplansPerStep = 1;

    /** 全局证据门控默认阈值 */
    private int minSourceCount = 3;
    private double minCoverageRate = 0.6d;
    private int minClusterCount = 1;

    /** 不可重规划原因码（命中后直接 ABORT） */
    private List<String> nonRetryableReasonCodes = new ArrayList<>();

    /** capability 级证据门控覆盖配置 */
    private Map<String, EvidenceRule> evidence = new LinkedHashMap<>();

    @Data
    public static class EvidenceRule {
        /** false 表示该 capability 关闭证据门控 */
        private Boolean enabled;
        private Integer minSourceCount;
        private Double minCoverageRate;
        private Integer minClusterCount;
    }
}

