package com.example.news.aggregation.agent.enums;

/**
 * 单轮请求执行状态。
 * <p>
 * 该状态与会话(Session)生命周期解耦，只描述当前 turn 的执行进度。
 */
public enum TurnStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    CANCELLED,
    BUSY
}

