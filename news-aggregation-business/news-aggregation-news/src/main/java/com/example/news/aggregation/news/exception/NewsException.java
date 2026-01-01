package com.example.news.aggregation.news.exception;

import com.example.news.aggregation.base.exception.BizException;
import com.example.news.aggregation.base.exception.ErrorCode;

public class NewsException extends BizException {

    public NewsException(ErrorCode errorCode){
        super(errorCode);
    }

    public NewsException(String message, ErrorCode errorCode) {
        super(message, errorCode);
    }
}
