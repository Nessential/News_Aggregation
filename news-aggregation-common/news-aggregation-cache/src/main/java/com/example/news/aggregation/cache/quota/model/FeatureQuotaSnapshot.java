package com.example.news.aggregation.cache.quota.model;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 单个功能的限额快照。
 */
@Data
@Builder
public class FeatureQuotaSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    private String featureCode;
    private boolean enabled;
    private int dailyLimit;
    private int usedCount;
    private int remainingCount;
    private long expireAtEpochMs;
}

