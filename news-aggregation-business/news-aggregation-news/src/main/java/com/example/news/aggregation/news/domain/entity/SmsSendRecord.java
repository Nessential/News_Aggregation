package com.example.news.aggregation.news.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.news.aggregation.datasource.domain.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 短信发送记录。
 */
@Getter
@Setter
@TableName("sms_send_record")
public class SmsSendRecord extends BaseEntity {

    /**
     * 业务请求 ID（对外可见）。
     */
    private String outId;

    /**
     * 手机号。
     */
    private String phone;

    /**
     * 业务场景，如 LOGIN。
     */
    private String scene;

    /**
     * 发送状态：INIT/SUCCESS/FAILED。
     */
    private String state;

    /**
     * 服务商返回码。
     */
    private String providerCode;

    /**
     * 服务商返回信息。
     */
    private String providerMessage;

    /**
     * 服务商请求 ID。
     */
    private String providerRequestId;

    /**
     * 发送成功时间。
     */
    private Date sendSuccessTime;
}
