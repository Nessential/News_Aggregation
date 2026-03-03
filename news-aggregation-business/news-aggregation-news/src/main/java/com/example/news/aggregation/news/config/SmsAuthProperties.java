package com.example.news.aggregation.news.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 短信认证配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "sms.aliyun")
public class SmsAuthProperties {

    /**
     * 是否启用阿里云短信认证。
     */
    private boolean enabled = false;

    /**
     * API Endpoint。
     */
    private String endpoint = "dypnsapi.aliyuncs.com";

    /**
     * 短信验证码方案名称（阿里云控制台配置）。
     */
    private String schemeName;

    /**
     * 短信签名。
     */
    private String signName;

    /**
     * 短信模板编码。
     */
    private String templateCode;

    /**
     * 短信模板参数（JSON 字符串）。
     */
    private String templateParam;

    /**
     * 国家码。
     */
    private String countryCode = "86";

    /**
     * 验证码长度。
     */
    private long codeLength = 6L;

    /**
     * 验证码有效期（秒）。
     */
    private long validTimeSeconds = 300L;

    /**
     * 重发间隔（秒）。
     */
    private long resendIntervalSeconds = 60L;

    /**
     * 限流窗口大小（秒，令牌桶）。
     */
    private long rateLimitWindowSeconds = 60L;

    /**
     * 限流窗口内最大允许发送次数。
     */
    private long rateLimitMaxRequests = 1L;

    /**
     * 本地调试模式的固定验证码（enabled=false 时生效）。
     */
    private String mockCode = "123456";
}
