package com.example.news.aggregation.news.service.impl;

import com.example.news.aggregation.base.exception.BizException;
import com.example.news.aggregation.cache.quota.model.FeatureQuotaSnapshot;
import com.example.news.aggregation.cache.quota.service.FeatureQuotaService;
import com.example.news.aggregation.news.config.SmsAuthProperties;
import com.example.news.aggregation.news.domain.entity.SmsSendRecord;
import com.example.news.aggregation.news.domain.entity.UserAccount;
import com.example.news.aggregation.news.dto.SmsLoginResponse;
import com.example.news.aggregation.news.dto.SmsSendCodeResponse;
import com.example.news.aggregation.news.exception.UserAuthErrorCode;
import com.example.news.aggregation.news.infrastructure.mapper.SmsSendRecordMapper;
import com.example.news.aggregation.news.infrastructure.mapper.UserAccountMapper;
import com.example.news.aggregation.news.service.SmsGateway;
import com.example.news.aggregation.news.service.TokenService;
import com.example.news.aggregation.news.service.UserAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserAuthServiceImpl implements UserAuthService {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^1\\d{10}$");
    private static final Pattern CODE_PATTERN = Pattern.compile("^\\d{4,8}$");
    private static final String SMS_OUT_ID_KEY = "user:sms:outid:%s";
    private static final String SMS_RATE_LIMIT_KEY = "user:sms:limit:%s";
    private static final String SMS_MOCK_CODE_KEY = "user:sms:mockcode:%s";
    private static final String SCENE_LOGIN = "LOGIN";
    private static final String STATE_INIT = "INIT";
    private static final String STATE_SUCCESS = "SUCCESS";
    private static final String STATE_FAILED = "FAILED";
    private static final int RECORD_UPDATE_MAX_RETRIES = 3;

    private final SmsSendRecordMapper smsSendRecordMapper;
    private final UserAccountMapper userAccountMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;
    private final SmsGateway smsGateway;
    private final SmsAuthProperties properties;
    private final TokenService tokenService;
    private final FeatureQuotaService featureQuotaService;

    @Override
    public SmsSendCodeResponse sendSmsCode(String phone) {
        validatePhone(phone);
        if (!allowSendByRateLimiter(phone)) {
            throw new BizException(UserAuthErrorCode.SMS_SEND_TOO_FREQUENT);
        }

        String outId = UUID.randomUUID().toString().replace("-", "");
        SmsSendRecord record = createInitRecord(phone, outId);
        if (properties.isEnabled()) {
            Thread.ofVirtual().name("sms-send-" + outId).start(() -> sendCodeAsync(record.getId(), phone, outId));
            log.info("[user-auth] \u9a8c\u8bc1\u7801\u53d1\u9001\u5df2\u53d7\u7406\uff08\u5f02\u6b65\uff09|phone={} |requestId={} |recordId={}",
                    maskPhone(phone), outId, record.getId());
        } else {
            String mockCodeKey = buildSmsMockCodeKey(phone);
            redisTemplate.opsForValue().set(
                    mockCodeKey,
                    properties.getMockCode(),
                    properties.getValidTimeSeconds(),
                    TimeUnit.SECONDS
            );
            markRecordSuccess(record.getId(), "MOCK_SUCCESS", "local mock mode", outId);
            log.info("[user-auth] \u672c\u5730\u8c03\u8bd5\u9a8c\u8bc1\u7801\u5df2\u751f\u6210|phone={} |mockCode={}",
                    maskPhone(phone), properties.getMockCode());
        }

        redisTemplate.opsForValue().set(buildSmsOutIdKey(phone), outId, properties.getValidTimeSeconds(), TimeUnit.SECONDS);
        log.info("[user-auth] \u9a8c\u8bc1\u7801\u53d1\u9001\u8bf7\u6c42\u5b8c\u6210|phone={} |enabled={} |expireSeconds={} |resendIntervalSeconds={} |requestId={}",
                maskPhone(phone), properties.isEnabled(), properties.getValidTimeSeconds(), properties.getRateLimitWindowSeconds(), outId);
        return SmsSendCodeResponse.builder()
                .success(true)
                .requestId(outId)
                .expireSeconds(properties.getValidTimeSeconds())
                .resendIntervalSeconds(properties.getRateLimitWindowSeconds())
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SmsLoginResponse loginWithSmsCode(String phone, String code) {
        validatePhone(phone);
        validateCode(code);
        String outId = (String) redisTemplate.opsForValue().get(buildSmsOutIdKey(phone));
        if (outId == null || outId.isBlank()) {
            throw new BizException(UserAuthErrorCode.SMS_NOT_SENT);
        }

        if (properties.isEnabled()) {
            SmsGateway.SmsVerifyResult result = smsGateway.verifyCode(phone, code, outId);
            if (!result.isPassed()) {
                log.warn("[user-auth] \u9a8c\u8bc1\u7801\u6821\u9a8c\u5931\u8d25|phone={} |providerCode={} |verifyResult={}",
                        maskPhone(phone), result.getProviderCode(), result.getVerifyResult());
                throw new BizException(UserAuthErrorCode.INVALID_SMS_CODE);
            }
        } else {
            String expected = (String) redisTemplate.opsForValue().get(buildSmsMockCodeKey(phone));
            if (expected == null || !expected.equals(code)) {
                throw new BizException(UserAuthErrorCode.INVALID_SMS_CODE);
            }
        }

        boolean isNewUser = false;
        UserAccount user = userAccountMapper.selectByPhone(phone);
        if (user == null) {
            user = new UserAccount();
            user.setPhone(phone);
            user.setEmail(null);
            user.setUsername(buildDefaultUsername(phone));
            int rows = userAccountMapper.insert(user);
            if (rows <= 0 || user.getId() == null) {
                throw new BizException(UserAuthErrorCode.USER_SAVE_FAILED);
            }
            isNewUser = true;
            log.info("[user-auth] \u65b0\u7528\u6237\u6ce8\u518c\u5b8c\u6210|userId={} |phone={} |username={}",
                    user.getId(), maskPhone(phone), user.getUsername());
        } else {
            log.info("[user-auth] \u8001\u7528\u6237\u767b\u5f55\u6210\u529f|userId={} |phone={}", user.getId(), maskPhone(phone));
        }

        redisTemplate.delete(buildSmsOutIdKey(phone));
        redisTemplate.delete(buildSmsMockCodeKey(phone));

        String token = tokenService.generateToken(user.getId());

        Map<String, FeatureQuotaSnapshot> featureQuotas = Collections.emptyMap();
        try {
            featureQuotas = featureQuotaService.queryAllQuotas(user.getId());
        } catch (Exception ex) {
            log.error("[quota] \u767b\u5f55\u540e\u67e5\u8be2\u7528\u6237\u9650\u989d\u5931\u8d25\uff0c\u6309\u7a7a\u7ed3\u679c\u8fd4\u56de|userId={}",
                    user.getId(), ex);
        }

        return SmsLoginResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .phone(user.getPhone())
                .newUser(isNewUser)
                .token(token)
                .featureQuotas(featureQuotas)
                .build();
    }

    @Override
    public Map<String, FeatureQuotaSnapshot> queryUserFeatureQuotas(Long userId) {
        if (userId == null || userId <= 0) {
            return Collections.emptyMap();
        }
        try {
            return featureQuotaService.queryAllQuotas(userId);
        } catch (Exception ex) {
            log.error("[quota] \u67e5\u8be2\u5f53\u524d\u7528\u6237\u9650\u989d\u5931\u8d25|userId={}", userId, ex);
            return Collections.emptyMap();
        }
    }

    private void validatePhone(String phone) {
        if (phone == null || !PHONE_PATTERN.matcher(phone).matches()) {
            throw new BizException(UserAuthErrorCode.INVALID_PHONE);
        }
    }

    private void validateCode(String code) {
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw new BizException(UserAuthErrorCode.INVALID_SMS_CODE);
        }
    }

    private String buildDefaultUsername(String phone) {
        if (phone == null || phone.length() < 4) {
            return "\u7528\u6237";
        }
        return "\u7528\u6237" + phone.substring(phone.length() - 4);
    }

    private String buildSmsOutIdKey(String phone) {
        return String.format(SMS_OUT_ID_KEY, phone);
    }

    private String buildSmsMockCodeKey(String phone) {
        return String.format(SMS_MOCK_CODE_KEY, phone);
    }

    private String buildSmsRateLimitKey(String phone) {
        return String.format(SMS_RATE_LIMIT_KEY, phone);
    }

    private SmsSendRecord createInitRecord(String phone, String outId) {
        SmsSendRecord record = new SmsSendRecord();
        record.setOutId(outId);
        record.setPhone(phone);
        record.setScene(SCENE_LOGIN);
        record.setState(STATE_INIT);
        int rows = smsSendRecordMapper.insert(record);
        if (rows <= 0 || record.getId() == null) {
            throw new BizException(UserAuthErrorCode.SMS_PROVIDER_ERROR);
        }
        return record;
    }

    private void sendCodeAsync(Long recordId, String phone, String outId) {
        try {
            SmsGateway.SmsSendResult result = smsGateway.sendCode(phone, outId);
            if (result.isSuccess()) {
                markRecordSuccess(recordId, result.getProviderCode(), result.getProviderMessage(), result.getProviderRequestId());
                return;
            }
            markRecordFailed(recordId, result.getProviderCode(), result.getProviderMessage(), result.getProviderRequestId());
            redisTemplate.delete(buildSmsOutIdKey(phone));
        } catch (Exception ex) {
            markRecordFailed(recordId, "SYSTEM_ERROR", ex.getMessage(), null);
            redisTemplate.delete(buildSmsOutIdKey(phone));
            log.error("[user-auth] \u5f02\u6b65\u53d1\u9001\u9a8c\u8bc1\u7801\u5f02\u5e38|phone={} |requestId={} |recordId={}",
                    maskPhone(phone), outId, recordId, ex);
        }
    }

    private void markRecordSuccess(Long recordId, String providerCode, String providerMessage, String providerRequestId) {
        updateRecordStateWithCas(recordId, STATE_SUCCESS, providerCode, providerMessage, providerRequestId, new Date());
    }

    private void markRecordFailed(Long recordId, String providerCode, String providerMessage, String providerRequestId) {
        updateRecordStateWithCas(recordId, STATE_FAILED, providerCode, providerMessage, providerRequestId, null);
        log.warn("[user-auth] \u9a8c\u8bc1\u7801\u53d1\u9001\u5931\u8d25\u5df2\u8bb0\u5f55|recordId={} |providerCode={} |providerMessage={}",
                recordId, providerCode, providerMessage);
    }

    private void updateRecordStateWithCas(Long recordId,
                                          String targetState,
                                          String providerCode,
                                          String providerMessage,
                                          String providerRequestId,
                                          Date sendSuccessTime) {
        for (int attempt = 1; attempt <= RECORD_UPDATE_MAX_RETRIES; attempt++) {
            SmsSendRecord current = smsSendRecordMapper.selectById(recordId);
            if (current == null) {
                log.warn("[user-auth] \u77ed\u4fe1\u53d1\u9001\u8bb0\u5f55\u4e0d\u5b58\u5728\uff0c\u8df3\u8fc7\u72b6\u6001\u66f4\u65b0|recordId={} |targetState={}",
                        recordId, targetState);
                return;
            }

            String currentState = current.getState();
            if (!STATE_INIT.equals(currentState)) {
                if (targetState.equals(currentState)) {
                    return;
                }
                log.warn("[user-auth] \u77ed\u4fe1\u53d1\u9001\u8bb0\u5f55\u72b6\u6001\u5df2\u53d8\u5316\uff0c\u8df3\u8fc7\u8986\u76d6|recordId={} |currentState={} |targetState={}",
                        recordId, currentState, targetState);
                return;
            }

            Integer expectedLockVersion = current.getLockVersion() == null ? 0 : current.getLockVersion();
            int rows = smsSendRecordMapper.updateStateWithCas(
                    recordId,
                    expectedLockVersion,
                    STATE_INIT,
                    targetState,
                    providerCode,
                    providerMessage,
                    providerRequestId,
                    sendSuccessTime
            );
            if (rows > 0) {
                return;
            }
            log.warn("[user-auth] \u77ed\u4fe1\u53d1\u9001\u8bb0\u5f55 CAS \u66f4\u65b0\u51b2\u7a81\uff0c\u51c6\u5907\u91cd\u8bd5|recordId={} |targetState={} |attempt={}",
                    recordId, targetState, attempt);
        }

        log.error("[user-auth] \u77ed\u4fe1\u53d1\u9001\u8bb0\u5f55 CAS \u66f4\u65b0\u5931\u8d25\uff08\u8d85\u8fc7\u6700\u5927\u91cd\u8bd5\uff09|recordId={} |targetState={}",
                recordId, targetState);
    }

    private boolean allowSendByRateLimiter(String phone) {
        long windowSeconds = Math.max(properties.getRateLimitWindowSeconds(), 1L);
        long maxRequests = Math.max(properties.getRateLimitMaxRequests(), 1L);
        String limiterKey = buildSmsRateLimitKey(phone);
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(limiterKey);
        rateLimiter.trySetRate(RateType.OVERALL, maxRequests, windowSeconds, RateIntervalUnit.SECONDS);
        boolean allowed = rateLimiter.tryAcquire(1);
        if (!allowed) {
            log.warn("[user-auth] \u77ed\u4fe1\u9650\u6d41\u547d\u4e2d|phone={} |windowSeconds={} |maxRequests={}",
                    maskPhone(phone), windowSeconds, maxRequests);
        }
        return allowed;
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
