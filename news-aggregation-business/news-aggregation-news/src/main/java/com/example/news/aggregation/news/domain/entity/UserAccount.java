package com.example.news.aggregation.news.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.news.aggregation.datasource.domain.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户实体（短信登录/注册）。
 */
@Getter
@Setter
@TableName("user_account")
public class UserAccount extends BaseEntity {

    /**
     * 用户名，可重复。
     */
    private String username;

    /**
     * 邮箱，可为空。
     */
    private String email;

    /**
     * 手机号，唯一。
     */
    private String phone;
}
