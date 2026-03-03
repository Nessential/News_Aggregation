package com.example.news.aggregation.news.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SmsLoginRequest {

    @NotBlank(message = "phone 不能为空")
    private String phone;

    @NotBlank(message = "code 不能为空")
    private String code;
}

