package com.example.news.aggregation.news.exception;

import com.example.news.aggregation.base.exception.ErrorCode;

/**
 * 新闻模块错误码
 *
 * @author NewsAggregation
 */

public enum NewsErrorCode implements ErrorCode {

    RSS_PARSE_FAILED("RSS_PARSE_FAILED", "RSS 源解析失败"),
    RSS_SOURCE_NOT_FOUND("RSS_SOURCE_NOT_FOUND", "RSS 源不存在"),
    RSS_NETWORK_ERROR("RSS_NETWORK_ERROR", "RSS 源网络请求失败"),
    NEWS_SAVE_FAILED("NEWS_SAVE_FAILED", "新闻保存失败"),
    NEWS_NOT_FOUND("NEWS_NOT_FOUND", "新闻不存在"),
    CONTENT_FETCH_FAILED("CONTENT_FETCH_FAILED", "正文抓取失败"),
    TRANSLATION_FAILED("TRANSLATION_FAILED", "翻译失败"),
    TRANSLATION_API_ERROR("TRANSLATION_API_ERROR", "翻译API调用失败");
    private final String code;
    private final String message;

    NewsErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }

}
