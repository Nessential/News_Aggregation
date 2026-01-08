package com.example.news.aggregation.vector.exception;

import com.example.news.aggregation.base.exception.BizException;
import com.example.news.aggregation.base.exception.ErrorCode;

/**
 * 向量数据库业务异常类
 * 用于处理向量操作相关的业务异常
 * 
 * @author system
 */
public class VectorException extends BizException {
    
    /**
     * 构造函数 - 仅包含错误码
     * 
     * @param errorCode 错误码
     */
    public VectorException(ErrorCode errorCode) {
        super(errorCode);
    }

    /**
     * 构造函数 - 包含自定义消息和错误码
     * 
     * @param message 自定义错误消息
     * @param errorCode 错误码
     */
    public VectorException(String message, ErrorCode errorCode) {
        super(message, errorCode);
    }

    /**
     * 构造函数 - 包含自定义消息、原因异常和错误码
     * 
     * @param message 自定义错误消息
     * @param cause 原因异常
     * @param errorCode 错误码
     */
    public VectorException(String message, Throwable cause, ErrorCode errorCode) {
        super(message, cause, errorCode);
    }

    /**
     * 构造函数 - 包含原因异常和错误码
     * 
     * @param cause 原因异常
     * @param errorCode 错误码
     */
    public VectorException(Throwable cause, ErrorCode errorCode) {
        super(cause, errorCode);
    }
}