package com.example.news.aggregation.news.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SmsSendCodeRequest {

    @NotBlank(message = "phone 不能为空")
    private String phone;
}

