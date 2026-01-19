package com.example.news.aggregation.core.domain;

/**
 * FSM状态枚举（15状态完整版）
 * 对齐agent架构终版.md的15状态设计
 */
public enum FSMState {
    /**
     * 请求入口，初始化request_id/budget
     */
    START,

    /**
     * 意图识别+槽位提取+指代消解
     */
    ROUTE,

    /**
     * 追问缺失槽位（时间/主体/地区等）
     */
    NEED_CLARIFY,

    /**
     * 复杂任务拆解（Compare/Digest/Timeline常用）
     */
    PLAN,

    /**
     * 检索召回（BM25/Hybrid），输出候选
     */
    RETRIEVE,

    /**
     * 重排候选（cross-encoder）
     */
    RERANK,

    /**
     * 去重聚合为事件（story/event），按时间窗组织
     */
    CANONICALIZE,

    /**
     * 构建EvidencePack；生成spans；门槛检查
     */
    BUILD_EVIDENCE,

    /**
     * 证据不足的恢复策略（最多3次）
     */
    EVIDENCE_INSUFFICIENT,

    /**
     * 基于EvidencePack生成答案
     */
    GENERATE,

    /**
     * 检查引用覆盖/冲突/夸大
     */
    VALIDATE,

    /**
     * 用户确认写操作（订阅/草稿/生成任务）
     */
    CONFIRM,

    /**
     * 启动Temporal workflow
     */
    DISPATCH_ASYNC,

    /**
     * 查询进度/系统回推结果
     */
    RUNNING_ASYNC,

    /**
     * 成功完成，写回session/audit，返回响应
     */
    DONE,

    /**
     * 安全兜底，返回保守答+已检索候选
     */
    FAIL_SAFE
}
