package com.example.news.aggregation.news.dto;

import com.example.news.aggregation.cache.quota.model.FeatureQuotaSnapshot;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class SmsLoginResponse {

    private Long userId;

    private String username;

    private String email;

    private String phone;

    /** Whether current login created a new user. */
    private Boolean newUser;

    /** Login token, should be sent in Authorization header as Bearer token. */
    private String token;

    /** All feature quota snapshots for current user. */
    private Map<String, FeatureQuotaSnapshot> featureQuotas;
}
