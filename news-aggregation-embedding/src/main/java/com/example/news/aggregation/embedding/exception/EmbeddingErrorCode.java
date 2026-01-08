package com.example.news.aggregation.embedding.exception;

import com.example.news.aggregation.base.exception.ErrorCode;

public enum EmbeddingErrorCode implements ErrorCode {
    API_CALL_FAILED("EMBEDDING_001", "Embedding API 调用失败"),
    INVALID_RESPONSE("EMBEDDING_002", "Embedding API 响应格式无效"),
    EMPTY_INPUT("EMBEDDING_003", "输入文本不能为空"),
    RATE_LIMIT_EXCEEDED("EMBEDDING_004", "API 调用频率超限"),
    AUTHENTICATION_FAILED("EMBEDDING_005", "API 认证失败"),
    CONNECTION_TIMEOUT("EMBEDDING_006", "连接超时");

    private final String code;
    private final String message;

    EmbeddingErrorCode(String code, String message) {
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