package com.example.news.aggregation.news.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SmsSendCodeResponse {

    /**
     * 是否受理成功。
     */
    private Boolean success;

    /**
     * 本次发送请求 ID（对应 outId）。
     */
    private String requestId;

    /**
     * 验证码有效期（秒）。
     */
    private Long expireSeconds;

    /**
     * 允许再次发送的间隔（秒）。
     */
    private Long resendIntervalSeconds;
}
