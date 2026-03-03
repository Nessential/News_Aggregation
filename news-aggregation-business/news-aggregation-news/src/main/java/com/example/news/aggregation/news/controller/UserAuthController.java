package com.example.news.aggregation.news.controller;

import com.example.news.aggregation.news.dto.SmsLoginRequest;
import com.example.news.aggregation.news.dto.SmsLoginResponse;
import com.example.news.aggregation.news.dto.SmsSendCodeRequest;
import com.example.news.aggregation.news.dto.SmsSendCodeResponse;
import com.example.news.aggregation.news.service.UserAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/user/auth")
@RequiredArgsConstructor
public class UserAuthController {

    private final UserAuthService userAuthService;

    @PostMapping("/sms/send-code")
    public ResponseEntity<SmsSendCodeResponse> sendSmsCode(@Valid @RequestBody SmsSendCodeRequest request) {
        log.info("[user-auth] 请求发送验证码|phone={}", maskPhone(request.getPhone()));
        SmsSendCodeResponse response = userAuthService.sendSmsCode(request.getPhone());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/sms/login")
    public ResponseEntity<SmsLoginResponse> loginWithSms(@Valid @RequestBody SmsLoginRequest request) {
        log.info("[user-auth] 短信登录请求|phone={}", maskPhone(request.getPhone()));
        SmsLoginResponse response = userAuthService.loginWithSmsCode(request.getPhone(), request.getCode());
        return ResponseEntity.ok(response);
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}

