package com.example.news.aggregation.cache.quota.service;

import com.example.news.aggregation.cache.quota.model.FeatureQuotaCheckResult;
import com.example.news.aggregation.cache.quota.model.FeatureQuotaConsumeResult;
import com.example.news.aggregation.cache.quota.model.FeatureQuotaSnapshot;

import java.util.Map;

/**
 * 功能限额服务。
 */
public interface FeatureQuotaService {

    /**
     * 请求前检查限额（不扣减）。
     */
    FeatureQuotaCheckResult checkQuota(Long userId, String featureCode);

    /**
     * 请求成功后扣减限额。
     */
    FeatureQuotaConsumeResult consumeQuota(Long userId, String featureCode);

    /**
     * 查询用户当前所有功能限额快照。
     */
    Map<String, FeatureQuotaSnapshot> queryAllQuotas(Long userId);
}

