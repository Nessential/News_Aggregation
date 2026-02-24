package com.example.news.aggregation.agent.enums;

/**
 * 对话状态枚举。
 * 定义 Agent 与用户对话的 FSM 状态。
 */
public enum ConversationState {

    /** 请求入口 */
    START,

    /** 意图识别与路由 */
    ROUTE,

    /** 需要澄清 */
    NEED_CLARIFY,

    /** 任务规划 */
    PLAN,

    /** 检索召回 */
    RETRIEVE,

    /** 重排序 */
    RERANK,

    /** 归并去重 */
    CANONICALIZE,

    /** 构建证据 */
    BUILD_EVIDENCE,

    /** 证据不足 */
    EVIDENCE_INSUFFICIENT,

    /** 生成回答 */
    GENERATE,

    /** 输出校验 */
    VALIDATE,

    /** 用户确认 */
    CONFIRM,

    /** 异步任务分发 */
    DISPATCH_ASYNC,

    /** 异步任务执行中 */
    RUNNING_ASYNC,

    /** 成功完成 */
    DONE,

    /** 安全兜底 */
    FAIL_SAFE
}