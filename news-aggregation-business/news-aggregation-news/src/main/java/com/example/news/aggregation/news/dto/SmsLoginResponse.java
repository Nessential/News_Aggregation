package com.example.news.aggregation.news.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SmsLoginResponse {

    private Long userId;

    private String username;

    private String email;

    private String phone;

    /**
     * 是否是本次登录中新创建的用户。
     */
    private Boolean newUser;

    /**
     * 登录Token，前端需要将其放在 Authorization: Bearer {token} 请求头中。
     */
    private String token;
}

