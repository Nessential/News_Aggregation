package com.example.news.aggregation.news.dto;

import com.example.news.aggregation.cache.quota.model.FeatureQuotaSnapshot;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class UserQuotaResponse {

    private Long userId;

    private Map<String, FeatureQuotaSnapshot> featureQuotas;
}

