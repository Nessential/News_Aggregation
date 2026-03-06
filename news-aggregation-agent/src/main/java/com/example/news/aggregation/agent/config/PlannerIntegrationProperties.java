package com.example.news.aggregation.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SpringAI Alibaba 改造开关。
 *
 * <p>设计目标：</p>
 * <p>1. 通过配置精确控制 Planner 路径，支持 A/B 灰度。</p>
 * <p>2. 明确 Tool 绑定模式，但不改变执行期 selector/circuit/fallback 机制。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.agent")
public class PlannerIntegrationProperties {

    private Planner planner = new Planner();
    private Tool tool = new Tool();
    private Graph graph = new Graph();

    @Data
    public static class Planner {
        /**
         * 规划模式：
         * legacy：沿用历史逻辑（仅复杂任务触发 planner）
         * hybrid：模板优先，复杂任务走 planner
         * saa_graph：尽量走 planner（直答类除外）
         */
        private String mode = "hybrid";
    }

    @Data
    public static class Tool {
        private Binding binding = new Binding();

        @Data
        public static class Binding {
            /**
             * 工具绑定模式：
             * legacy：保留现有绑定方式
             * spring_tool：启用 SpringAI Tool 注解体系
             */
            private String mode = "legacy";
        }
    }

    @Data
    public static class Graph {
    }

    public PlannerMode resolvePlannerMode() {
        return PlannerMode.fromValue(planner == null ? null : planner.getMode());
    }

    public String resolveToolBindingMode() {
        if (tool == null || tool.getBinding() == null || tool.getBinding().getMode() == null) {
            return "legacy";
        }
        return tool.getBinding().getMode().trim().toLowerCase();
    }

    public enum PlannerMode {
        LEGACY,
        HYBRID,
        SAA_GRAPH;

        public static PlannerMode fromValue(String raw) {
            if (raw == null || raw.isBlank()) {
                return HYBRID;
            }
            String normalized = raw.trim().toUpperCase();
            return switch (normalized) {
                case "LEGACY" -> LEGACY;
                case "SAA_GRAPH" -> SAA_GRAPH;
                default -> HYBRID;
            };
        }
    }
}
