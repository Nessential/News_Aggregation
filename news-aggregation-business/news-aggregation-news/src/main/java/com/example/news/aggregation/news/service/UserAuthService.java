package com.example.news.aggregation.news.service;

import com.example.news.aggregation.cache.quota.model.FeatureQuotaSnapshot;
import com.example.news.aggregation.news.dto.SmsLoginResponse;
import com.example.news.aggregation.news.dto.SmsSendCodeResponse;

import java.util.Map;

public interface UserAuthService {

    SmsSendCodeResponse sendSmsCode(String phone);

    SmsLoginResponse loginWithSmsCode(String phone, String code);

    Map<String, FeatureQuotaSnapshot> queryUserFeatureQuotas(Long userId);
}
