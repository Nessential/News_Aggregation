package com.example.news.aggregation.base.util.translate;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "translate.baidu")
public class BaiduTranslateProperties {

    /**
     * 百度翻译 APP ID
     */
    private String appId;

    /**
     * 百度翻译密钥
     */
    private String secretKey;

    /**
     * API 地址
     */
    private String apiUrl = "https://fanyi-api.baidu.com/api/trans/vip/translate";
}