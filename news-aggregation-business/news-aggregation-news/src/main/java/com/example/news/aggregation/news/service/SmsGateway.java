package com.example.news.aggregation.news.service;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 短信网关统一抽象。
 */
public interface SmsGateway {

    SmsSendResult sendCode(String phone, String outId);

    SmsVerifyResult verifyCode(String phone, String code, String outId);

    @Data
    @AllArgsConstructor
    class SmsSendResult {
        private boolean success;
        private String providerCode;
        private String providerMessage;
        private String providerRequestId;
    }

    @Data
    @AllArgsConstructor
    class SmsVerifyResult {
        private boolean passed;
        private String providerCode;
        private String providerMessage;
        private String verifyResult;
    }
}
