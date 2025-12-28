package com.example.news.aggregation.base.exception;

/**
 * 错误码
 *
 * @author Hollis
 */
public interface ErrorCode {
    /**
     * 错误码
     *
     * @return 错误码
     */
    String getCode();

    /**
     * 错误信息
     *
     * @return 错误信息
     */
    String getMessage();
}
