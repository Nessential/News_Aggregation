package com.example.news.aggregation.core.domain;

/**
 * FSM状态枚举（Phase 0: 8个状态）
 */
public enum FSMState {
    /**
     * 初始状态
     */
    INIT,

    /**
     * 理解意图
     */
    UNDERSTAND_INTENT,

    /**
     * 构建查询
     */
    BUILD_QUERY,

    /**
     * 检索证据
     */
    RETRIEVE_EVIDENCE,

    /**
     * 合成答案
     */
    SYNTHESIZE_ANSWER,

    /**
     * 澄清问题
     */
    CLARIFY,

    /**
     * 终止状态
     */
    TERMINAL,

    /**
     * 错误状态
     */
    ERROR
}