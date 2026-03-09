package com.example.news.aggregation.rpc.contract;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ? */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionConstraints implements Serializable {
    private static final long serialVersionUID = 1L;
    private Integer maxSteps;
    private Integer maxToolCalls;
    private Integer maxTokens;
    private Long timeoutMs;
}

