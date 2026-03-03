package com.example.news.aggregation.news.service.impl;

import com.example.news.aggregation.base.exception.BizException;
import com.example.news.aggregation.news.config.SmsAuthProperties;
import com.example.news.aggregation.news.domain.entity.SmsSendRecord;
import com.example.news.aggregation.news.domain.entity.UserAccount;
import com.example.news.aggregation.news.dto.SmsLoginResponse;
import com.example.news.aggregation.news.dto.SmsSendCodeResponse;
import com.example.news.aggregation.news.exception.UserAuthErrorCode;
import com.example.news.aggregation.news.infrastructure.mapper.SmsSendRecordMapper;
import com.example.news.aggregation.news.infrastructure.mapper.UserAccountMapper;
import com.example.news.aggregation.news.service.SmsGateway;
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

import java.util.Date;
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

    @Override
    public SmsSendCodeResponse sendSmsCode(String phone) {
        validatePhone(phone);
        if (!allowSendByRateLimiter(phone)) {
            throw new BizException(UserAuthErrorCode.SMS_SEND_TOO_FREQUENT);
        }

        String outId = UUID.randomUUID().toString().replace("-", "");
        SmsSendRecord record = createInitRecord(phone, outId);
        if (properties.isEnabled()) {
            // 快速返回：后台虚拟线程执行同步网关调用。
            Thread.ofVirtual().name("sms-send-" + outId).start(() -> sendCodeAsync(record.getId(), phone, outId));
            log.info("[user-auth] 验证码发送已受理（异步）|phone={} |requestId={} |recordId={}",
                    maskPhone(phone), outId, record.getId());
        } else {
            // 本地调试模式：固定验证码写入 Redis，便于前后端联调。
            String mockCodeKey = buildSmsMockCodeKey(phone);
            redisTemplate.opsForValue().set(
                    mockCodeKey,
                    properties.getMockCode(),
                    properties.getValidTimeSeconds(),
                    TimeUnit.SECONDS
            );
            markRecordSuccess(record.getId(), "MOCK_SUCCESS", "local mock mode", outId);
            log.info("[user-auth] 本地调试验证码已生成|phone={} |mockCode={}", maskPhone(phone), properties.getMockCode());
        }

        redisTemplate.opsForValue().set(buildSmsOutIdKey(phone), outId, properties.getValidTimeSeconds(), TimeUnit.SECONDS);
        log.info("[user-auth] 验证码发送请求完成|phone={} |enabled={} |expireSeconds={} |resendIntervalSeconds={} |requestId={}",
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
                log.warn("[user-auth] 验证码校验失败|phone={} |providerCode={} |verifyResult={}",
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
            log.info("[user-auth] 新用户注册完成|userId={} |phone={} |username={}",
                    user.getId(), maskPhone(phone), user.getUsername());
        } else {
            log.info("[user-auth] 老用户登录成功|userId={} |phone={}", user.getId(), maskPhone(phone));
        }

        // 登录成功后清理验证码上下文，避免重复使用。
        redisTemplate.delete(buildSmsOutIdKey(phone));
        redisTemplate.delete(buildSmsMockCodeKey(phone));
        return SmsLoginResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .phone(user.getPhone())
                .newUser(isNewUser)
                .build();
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
            return "用户";
        }
        return "用户" + phone.substring(phone.length() - 4);
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
            // 发送失败时删除 outId，避免进入无效校验流程；由用户重新触发发送。
            redisTemplate.delete(buildSmsOutIdKey(phone));
        } catch (Exception ex) {
            markRecordFailed(recordId, "SYSTEM_ERROR", ex.getMessage(), null);
            redisTemplate.delete(buildSmsOutIdKey(phone));
            log.error("[user-auth] 异步发送验证码异常|phone={} |requestId={} |recordId={}",
                    maskPhone(phone), outId, recordId, ex);
        }
    }

    private void markRecordSuccess(Long recordId, String providerCode, String providerMessage, String providerRequestId) {
        updateRecordStateWithCas(recordId, STATE_SUCCESS, providerCode, providerMessage, providerRequestId, new Date());
    }

    private void markRecordFailed(Long recordId, String providerCode, String providerMessage, String providerRequestId) {
        updateRecordStateWithCas(recordId, STATE_FAILED, providerCode, providerMessage, providerRequestId, null);
        log.warn("[user-auth] 验证码发送失败已记录|recordId={} |providerCode={} |providerMessage={}",
                recordId, providerCode, providerMessage);
    }

    /**
     * 使用 id + lock_version + fromState 做 CAS 更新，避免并发覆盖短信发送状态。
     */
    private void updateRecordStateWithCas(Long recordId,
                                          String targetState,
                                          String providerCode,
                                          String providerMessage,
                                          String providerRequestId,
                                          Date sendSuccessTime) {
        for (int attempt = 1; attempt <= RECORD_UPDATE_MAX_RETRIES; attempt++) {
            SmsSendRecord current = smsSendRecordMapper.selectById(recordId);
            if (current == null) {
                log.warn("[user-auth] 短信发送记录不存在，跳过状态更新|recordId={} |targetState={}",
                        recordId, targetState);
                return;
            }

            String currentState = current.getState();
            if (!STATE_INIT.equals(currentState)) {
                if (targetState.equals(currentState)) {
                    return;
                }
                log.warn("[user-auth] 短信发送记录状态已变化，跳过覆盖|recordId={} |currentState={} |targetState={}",
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
            log.warn("[user-auth] 短信发送记录 CAS 更新冲突，准备重试|recordId={} |targetState={} |attempt={}",
                    recordId, targetState, attempt);
        }

        log.error("[user-auth] 短信发送记录 CAS 更新失败（超过最大重试）|recordId={} |targetState={}",
                recordId, targetState);
    }

    /**
     * 使用 Redisson RRateLimiter 做按手机号限流（令牌桶模型）。
     */
    private boolean allowSendByRateLimiter(String phone) {
        long windowSeconds = Math.max(properties.getRateLimitWindowSeconds(), 1L);
        long maxRequests = Math.max(properties.getRateLimitMaxRequests(), 1L);
        String limiterKey = buildSmsRateLimitKey(phone);
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(limiterKey);
        rateLimiter.trySetRate(RateType.OVERALL, maxRequests, windowSeconds, RateIntervalUnit.SECONDS);
        boolean allowed = rateLimiter.tryAcquire(1);
        if (!allowed) {
            log.warn("[user-auth] 短信限流命中|phone={} |windowSeconds={} |maxRequests={}",
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
