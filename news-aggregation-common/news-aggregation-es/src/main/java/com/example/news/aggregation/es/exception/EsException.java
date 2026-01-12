package com.example.news.aggregation.es.exception;

import com.example.news.aggregation.base.exception.BizException;
import com.example.news.aggregation.base.exception.ErrorCode;

/**
 * Elasticsearch 异常
 */
public class EsException extends BizException {

    /**
     * 构造函数 - 仅包含错误码
     */
    public EsException(ErrorCode errorCode) {
        super(errorCode);
    }

    /**
     * 构造函数 - 包含自定义消息和错误码
     */
    public EsException(String message, ErrorCode errorCode) {
        super(message, errorCode);
    }

    /**
     * 构造函数 - 包含自定义消息、原因异常和错误码
     */
    public EsException(String message, Throwable cause, ErrorCode errorCode) {
        super(message, cause, errorCode);
    }

    /**
     * 构造函数 - 包含原因异常和错误码
     */
    public EsException(Throwable cause, ErrorCode errorCode) {
        super(cause, errorCode);
    }
}
