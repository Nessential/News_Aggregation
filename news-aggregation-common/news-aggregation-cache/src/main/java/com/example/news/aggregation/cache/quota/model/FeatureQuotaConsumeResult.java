package com.example.news.aggregation.cache.quota.model;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 限额扣减结果。
 */
@Data
@Builder
public class FeatureQuotaConsumeResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private boolean consumed;
    private String reasonCode;
    private String reasonMessage;
    private Map<String, FeatureQuotaSnapshot> allQuotas;
}

