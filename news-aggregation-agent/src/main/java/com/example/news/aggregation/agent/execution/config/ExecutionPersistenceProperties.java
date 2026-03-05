package com.example.news.aggregation.agent.execution.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 执行持久化配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.agent.execution")
public class ExecutionPersistenceProperties {

    private Persistence persistence = new Persistence();
    private Dedupe dedupe = new Dedupe();
    private Recovery recovery = new Recovery();

    @Data
    public static class Persistence {
        /** 是否启用持久化执行。 */
        private boolean enabled = true;
        /** 当前节点的执行器ID。 */
        private String workerId = "local-worker";
        /** step 租约秒数。 */
        private long stepLeaseSeconds = 30;
        /** 心跳续租秒数。 */
        private long heartbeatSeconds = 10;
        /** 是否允许接管过期 RUNNING。 */
        private boolean takeoverEnabled = true;
    }

    @Data
    public static class Dedupe {
        /** 是否在 dedupe 逻辑中校验 planHash。 */
        private boolean includePlanHash = true;
    }

    @Data
    public static class Recovery {
        /** 是否启用恢复扫描。 */
        private boolean enabled = true;
        /** 扫描间隔（毫秒）。 */
        private long scanIntervalMs = 15_000;
        /** 每次扫描批量。 */
        private int scanBatchSize = 100;
        /** 默认最大恢复次数。 */
        private int maxRecoveryAttempts = 5;
    }
}
