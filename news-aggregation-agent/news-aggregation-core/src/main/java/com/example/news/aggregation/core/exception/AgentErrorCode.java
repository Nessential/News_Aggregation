package com.example.news.aggregation.core.exception;

import com.example.news.aggregation.base.exception.ErrorCode;

/**
 * Agent模块错误码
 */
public enum AgentErrorCode implements ErrorCode {

    /**
     * Session不存在
     */
    SESSION_NOT_FOUND("SESSION_NOT_FOUND", "Session不存在"),

    /**
     * 并发访问冲突
     */
    CONCURRENT_ACCESS_CONFLICT("CONCURRENT_ACCESS_CONFLICT", "并发访问冲突，请重试"),

    /**
     * Session锁获取失败
     */
    SESSION_LOCK_FAILED("SESSION_LOCK_FAILED", "Session锁获取失败"),

    /**
     * FSM状态转换失败
     */
    FSM_TRANSITION_FAILED("FSM_TRANSITION_FAILED", "FSM状态转换失败"),

    /**
     * LLM调用失败
     */
    LLM_CALL_FAILED("LLM_CALL_FAILED", "LLM调用失败");

    private String code;
    private String message;

    AgentErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String getCode() {
        return this.code;
    }

    @Override
    public String getMessage() {
        return this.message;
    }
}