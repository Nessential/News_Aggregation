package com.example.news.aggregation.news.exception;

import com.example.news.aggregation.base.exception.ErrorCode;

/**
 * 用户短信认证相关错误码。
 */
public enum UserAuthErrorCode implements ErrorCode {

    INVALID_PHONE("INVALID_PHONE", "手机号格式不正确"),
    INVALID_SMS_CODE("INVALID_SMS_CODE", "验证码错误或已过期"),
    SMS_SEND_TOO_FREQUENT("SMS_SEND_TOO_FREQUENT", "验证码发送过于频繁，请稍后重试"),
    SMS_NOT_SENT("SMS_NOT_SENT", "请先获取验证码"),
    SMS_PROVIDER_ERROR("SMS_PROVIDER_ERROR", "短信服务调用失败"),
    USER_SAVE_FAILED("USER_SAVE_FAILED", "用户保存失败");

    private final String code;
    private final String message;

    UserAuthErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}

