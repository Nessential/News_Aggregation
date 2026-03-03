package com.example.news.aggregation.news.service;

import com.example.news.aggregation.news.dto.SmsLoginResponse;
import com.example.news.aggregation.news.dto.SmsSendCodeResponse;

public interface UserAuthService {

    SmsSendCodeResponse sendSmsCode(String phone);

    SmsLoginResponse loginWithSmsCode(String phone, String code);
}

