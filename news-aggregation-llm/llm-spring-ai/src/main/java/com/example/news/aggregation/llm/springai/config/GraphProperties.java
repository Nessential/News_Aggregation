package com.example.news.aggregation.llm.springai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Graph配置
 * 控制Router/Planner/Generator图的启用与执行参数
 */
@Data
@ConfigurationProperties(prefix = "app.llm.graph")
public class GraphProperties {

    /** 是否启用RouterGraph */
    private boolean routerEnabled = true;
    /** 是否启用PlannerGraph */
    private boolean plannerEnabled = true;
    /** 是否启用GeneratorGraph */
    private boolean generatorEnabled = true;
    /** Graph执行超时秒数 */
    private int timeoutSeconds = 30;
    /** GeneratorGraph最大迭代次数 */
    private int maxIterations = 5;
    /** 是否启用检查点 */
    private boolean checkpointEnabled = false;
}