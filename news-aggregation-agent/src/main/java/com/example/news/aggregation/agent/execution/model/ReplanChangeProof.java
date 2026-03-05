package com.example.news.aggregation.agent.execution.model;

import lombok.Builder;
import lombok.Value;

/**
 * 重规划变化证明。
 */
@Value
@Builder
public class ReplanChangeProof {
    boolean effectiveChange;
    String previousSignature;
    String candidateSignature;
    String reasonCode;
}

