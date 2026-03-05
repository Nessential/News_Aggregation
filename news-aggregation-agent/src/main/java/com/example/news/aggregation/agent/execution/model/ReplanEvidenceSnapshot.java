package com.example.news.aggregation.agent.execution.model;

import lombok.Builder;
import lombok.Value;

/**
 * 重规划证据快照。
 * v1 只读取流水线已产出的结构化统计，不引入额外算法。
 */
@Value
@Builder
public class ReplanEvidenceSnapshot {
    Integer sourceCount;
    Double coverageRate;
    Integer clusterCount;
}

