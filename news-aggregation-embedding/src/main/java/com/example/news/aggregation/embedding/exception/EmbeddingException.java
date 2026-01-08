package com.example.news.aggregation.embedding.exception;

import com.example.news.aggregation.base.exception.BizException;
import com.example.news.aggregation.base.exception.ErrorCode;

public class EmbeddingException extends BizException {
    public EmbeddingException(ErrorCode errorCode) {
        super(errorCode);
    }

    public EmbeddingException(String message, ErrorCode errorCode) {
        super(message, errorCode);
    }

    public EmbeddingException(String message, Throwable cause, ErrorCode errorCode) {
        super(message, cause, errorCode);
    }

    public EmbeddingException(Throwable cause, ErrorCode errorCode) {
        super(cause, errorCode);
    }
}