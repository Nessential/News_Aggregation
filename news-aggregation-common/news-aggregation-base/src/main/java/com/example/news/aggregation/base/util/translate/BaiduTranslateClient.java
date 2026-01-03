package com.example.news.aggregation.base.util.translate;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 百度翻译客户端
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BaiduTranslateClient {

    private final BaiduTranslateProperties properties;

    /**
     * 翻译文本（英文转中文）
     *
     * @param text 原文
     * @return 译文，失败返回null
     */
    public String translate(String text) {
        return translate(text, "en", "zh");
    }

    /**
     * 翻译文本
     *
     * @param text 原文
     * @param from 源语言
     * @param to   目标语言
     * @return 译文，失败返回null
     */
    public String translate(String text, String from, String to) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        try {
            String salt = String.valueOf(System.currentTimeMillis());
            String sign = md5(properties.getAppId() + text + salt + properties.getSecretKey());

            String urlStr = properties.getApiUrl() +
                    "?q=" + URLEncoder.encode(text, StandardCharsets.UTF_8) +
                    "&from=" + from +
                    "&to=" + to +
                    "&appid=" + properties.getAppId() +
                    "&salt=" + salt +
                    "&sign=" + sign;

            URL url = new URL(urlStr);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            // 解析响应
            JSONObject root = JSON.parseObject(response.toString());

            if (root.containsKey("error_code")) {
                log.error("百度翻译API错误: {}", response);
                return null;
            }

            JSONArray transResult = root.getJSONArray("trans_result");
            if (transResult != null && !transResult.isEmpty()) {
                return transResult.getJSONObject(0).getString("dst");
            }

            return null;

        } catch (Exception e) {
            log.error("翻译失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 计算MD5
     */
    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5计算失败", e);
        }
    }
}