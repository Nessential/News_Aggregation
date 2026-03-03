package com.example.news.aggregation.news.service;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.CheckSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.CheckSmsVerifyCodeResponse;
import com.aliyun.dypnsapi20170525.models.CheckSmsVerifyCodeResponseBody;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponseBody;
import com.aliyun.tea.TeaException;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.example.news.aggregation.news.config.SmsAuthProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 短信网关实现（当前使用阿里云 Dypnsapi）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmsSendGateway implements SmsGateway {

    private static final String MAINLAND_ENDPOINT = "dypnsapi.aliyuncs.com";
    private static final String DEFAULT_SCHEME_PLACEHOLDER = "your-aliyun-sms-scheme-name";

    private final SmsAuthProperties properties;
    private Client client;
    private String resolvedEndpoint;

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("[sms-auth] 短信认证未启用，当前使用本地调试模式");
            return;
        }
        try {
            this.resolvedEndpoint = normalizeEndpoint(properties.getEndpoint());
            validateEndpoint(this.resolvedEndpoint);
            Config config = new Config()
                    .setCredential(new com.aliyun.credentials.Client());
            config.setEndpoint(this.resolvedEndpoint);
            this.client = new Client(config);
            log.info("[sms-auth] 短信网关初始化完成|endpoint={} |schemeName={}",
                    this.resolvedEndpoint, properties.getSchemeName());
        } catch (Exception ex) {
            log.error("[sms-auth] 短信网关初始化失败", ex);
            throw new IllegalStateException("failed to initialize sms gateway", ex);
        }
    }

    @Override
    public SmsSendResult sendCode(String phone, String outId) {
        ensureEnabledAndReady();
        try {
            SendSmsVerifyCodeRequest request = new SendSmsVerifyCodeRequest()
                    .setPhoneNumber(phone)
                    .setSchemeName(properties.getSchemeName())
                    .setSignName(properties.getSignName())
                    .setTemplateCode(properties.getTemplateCode())
                    .setTemplateParam(properties.getTemplateParam())
                    .setCountryCode(properties.getCountryCode())
                    .setCodeLength(properties.getCodeLength())
                    .setValidTime(properties.getValidTimeSeconds())
                    .setOutId(outId);


            SendSmsVerifyCodeResponse response = client.sendSmsVerifyCodeWithOptions(request, new RuntimeOptions());
            SendSmsVerifyCodeResponseBody body = response.getBody();
            boolean success = body != null && Boolean.TRUE.equals(body.getSuccess());
            String providerCode = body != null ? body.getCode() : null;
            String providerMessage = body != null ? body.getMessage() : null;
            String requestId = body != null && body.getModel() != null ? body.getModel().getBizId() : null;
            log.info("[sms-auth] 发送验证码完成|phone={} |outId={} |success={} |providerCode={} |requestId={}",
                    maskPhone(phone), outId, success, providerCode, requestId);
            return new SmsSendResult(success, providerCode, providerMessage, requestId);
        } catch (TeaException ex) {
            log.error("[sms-auth] 发送验证码异常|phone={} |outId={} |endpoint={} |schemeName={} |teaCode={} |statusCode={} |teaMessage={} |teaData={}",
                    maskPhone(phone), outId, resolvedEndpoint, properties.getSchemeName(),
                    ex.getCode(), ex.getStatusCode(), ex.getMessage(), ex.getData(), ex);
            return new SmsSendResult(false, ex.getCode(), ex.getMessage(), null);
        } catch (Exception ex) {
            log.error("[sms-auth] 发送验证码异常|phone={} |outId={} |endpoint={} |schemeName={}",
                    maskPhone(phone), outId, resolvedEndpoint, properties.getSchemeName(), ex);
            return new SmsSendResult(false, "EXCEPTION", ex.getMessage(), null);
        }
    }

    @Override
    public SmsVerifyResult verifyCode(String phone, String code, String outId) {
        ensureEnabledAndReady();
        try {
            CheckSmsVerifyCodeRequest request = new CheckSmsVerifyCodeRequest()
                    .setPhoneNumber(phone)
                    .setVerifyCode(code)
                    .setSchemeName(properties.getSchemeName())
                    .setCountryCode(properties.getCountryCode())
                    .setOutId(outId);
            CheckSmsVerifyCodeResponse response = client.checkSmsVerifyCodeWithOptions(request, new RuntimeOptions());
            CheckSmsVerifyCodeResponseBody body = response.getBody();
            boolean success = body != null && Boolean.TRUE.equals(body.getSuccess());
            String providerCode = body != null ? body.getCode() : null;
            String providerMessage = body != null ? body.getMessage() : null;
            String verifyResult = body != null && body.getModel() != null ? body.getModel().getVerifyResult() : null;
            boolean passed = success && isPassResult(verifyResult);
            log.info("[sms-auth] 校验验证码完成|phone={} |outId={} |success={} |providerCode={} |verifyResult={}",
                    maskPhone(phone), outId, success, providerCode, verifyResult);
            return new SmsVerifyResult(passed, providerCode, providerMessage, verifyResult);
        } catch (TeaException ex) {
            log.error("[sms-auth] 校验验证码异常|phone={} |outId={} |endpoint={} |schemeName={} |teaCode={} |statusCode={} |teaMessage={} |teaData={}",
                    maskPhone(phone), outId, resolvedEndpoint, properties.getSchemeName(),
                    ex.getCode(), ex.getStatusCode(), ex.getMessage(), ex.getData(), ex);
            return new SmsVerifyResult(false, ex.getCode(), ex.getMessage(), "EXCEPTION");
        } catch (Exception ex) {
            log.error("[sms-auth] 校验验证码异常|phone={} |outId={} |endpoint={} |schemeName={}",
                    maskPhone(phone), outId, resolvedEndpoint, properties.getSchemeName(), ex);
            return new SmsVerifyResult(false, "EXCEPTION", ex.getMessage(), "EXCEPTION");
        }
    }

    private void ensureEnabledAndReady() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("sms auth is disabled");
        }
        if (client == null) {
            throw new IllegalStateException("sms gateway is not ready");
        }
        if (properties.getSchemeName() == null || properties.getSchemeName().isBlank()) {
            throw new IllegalStateException("sms schemeName is empty");
        }
        if (DEFAULT_SCHEME_PLACEHOLDER.equalsIgnoreCase(properties.getSchemeName().trim())) {
            throw new IllegalStateException("sms schemeName is placeholder, please set real scheme name");
        }
        if (properties.getSignName() == null || properties.getSignName().isBlank()) {
            throw new IllegalStateException("sms signName is empty");
        }
        if (properties.getTemplateCode() == null || properties.getTemplateCode().isBlank()) {
            throw new IllegalStateException("sms templateCode is empty");
        }
        if (properties.getTemplateParam() == null || properties.getTemplateParam().isBlank()) {
            throw new IllegalStateException("sms templateParam is empty");
        }
    }

    private String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return MAINLAND_ENDPOINT;
        }
        String normalized = endpoint.trim();
        if (normalized.startsWith("https://")) {
            normalized = normalized.substring("https://".length());
        } else if (normalized.startsWith("http://")) {
            normalized = normalized.substring("http://".length());
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void validateEndpoint(String endpoint) {
        String lowered = endpoint.toLowerCase(Locale.ROOT);
        if (lowered.contains("intl")) {
            throw new IllegalStateException("current sdk is dypnsapi20170525(mainland), intl endpoint is not supported: " + endpoint);
        }
    }

    private boolean isPassResult(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return "PASS".equals(normalized)
                || "SUCCESS".equals(normalized)
                || "TRUE".equals(normalized)
                || "OK".equals(normalized)
                || "1".equals(normalized);
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
