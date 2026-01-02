package com.example.news.aggregation.storage.exception;

import com.example.news.aggregation.base.exception.BizException;
import com.example.news.aggregation.base.exception.ErrorCode;

public class StorageException extends BizException {

    public StorageException(ErrorCode errorCode) {
        super(errorCode);
    }

    public StorageException(String message, ErrorCode errorCode) {
        super(message, errorCode);
    }

    public StorageException(String message, Throwable cause, ErrorCode errorCode) {
        super(message, cause, errorCode);
    }

    public StorageException(Throwable cause, ErrorCode errorCode) {
        super(cause, errorCode);
    }

    public StorageException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace, ErrorCode errorCode) {
        super(message, cause, enableSuppression, writableStackTrace, errorCode);
    }

}
