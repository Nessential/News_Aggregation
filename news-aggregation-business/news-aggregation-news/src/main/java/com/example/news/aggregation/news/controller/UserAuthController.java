package com.example.news.aggregation.news.controller;

import com.example.news.aggregation.news.dto.SmsLoginRequest;
import com.example.news.aggregation.news.dto.SmsLoginResponse;
import com.example.news.aggregation.news.dto.SmsSendCodeRequest;
import com.example.news.aggregation.news.dto.SmsSendCodeResponse;
import com.example.news.aggregation.news.dto.UserQuotaResponse;
import com.example.news.aggregation.news.service.UserAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/user/auth")
@RequiredArgsConstructor
public class UserAuthController {

    private final UserAuthService userAuthService;

    @PostMapping("/sms/send-code")
    public ResponseEntity<SmsSendCodeResponse> sendSmsCode(@Valid @RequestBody SmsSendCodeRequest request) {
        log.info("[user-auth] \u8bf7\u6c42\u53d1\u9001\u9a8c\u8bc1\u7801|phone={}", maskPhone(request.getPhone()));
        SmsSendCodeResponse response = userAuthService.sendSmsCode(request.getPhone());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/sms/login")
    public ResponseEntity<SmsLoginResponse> loginWithSms(@Valid @RequestBody SmsLoginRequest request) {
        log.info("[user-auth] \u77ed\u4fe1\u767b\u5f55\u8bf7\u6c42|phone={}", maskPhone(request.getPhone()));
        SmsLoginResponse response = userAuthService.loginWithSmsCode(request.getPhone(), request.getCode());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/quota/me")
    public ResponseEntity<UserQuotaResponse> getCurrentUserQuota(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        if (userIdHeader == null || userIdHeader.isBlank()) {
            log.warn("[user-auth] \u67e5\u8be2\u9650\u989d\u5931\u8d25|reason=missing_x_user_id");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Long userId;
        try {
            userId = Long.parseLong(userIdHeader.trim());
        } catch (Exception ex) {
            log.warn("[user-auth] \u67e5\u8be2\u9650\u989d\u5931\u8d25|reason=invalid_x_user_id|value={}", userIdHeader);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return ResponseEntity.ok(UserQuotaResponse.builder()
                .userId(userId)
                .featureQuotas(userAuthService.queryUserFeatureQuotas(userId))
                .build());
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
