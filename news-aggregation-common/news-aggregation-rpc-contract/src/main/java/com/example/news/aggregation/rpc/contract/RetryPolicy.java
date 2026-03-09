package com.example.news.aggregation.rpc.contract;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ? */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetryPolicy implements Serializable {
    private static final long serialVersionUID = 1L;
    private Integer maxRetries;
    private Long backoffMs;
    private List<String> retryableErrorCodes;
}

