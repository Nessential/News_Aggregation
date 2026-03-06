package com.example.news.aggregation.llm.springai.state;

import com.example.news.aggregation.llm.springai.contract.RouterResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * RouterGraph 状态。
 * 记录路由识别过程中的输入与输出。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouterState {

    /** 会话 ID */
    private String sessionId;

    /** 原始查询 */
    private String query;

    /** 指代消解后的查询 */
    private String resolvedQuery;

    /** 对话历史 */
    private List<String> history;

    /** 约束条件 */
    private Map<String, Object> constraints;

    /** 任务类型 */
    private String taskFamily;

    /** 检索模式 */
    private String retrievalMode;

    /** 风险级别 */
    private String riskLevel;

    /** 是否需要澄清 */
    private Boolean needsClarification;

    /** 澄清问题 */
    private String clarificationQuestion;

    /** 抽取参数 */
    private Map<String, Object> params;

    /** 一级意图范围：NEWS/NON_NEWS */
    private String intentScope;

    /** 一级意图置信度 */
    private Double intentConfidence;

    /** 一级意图原因 */
    private String intentReason;

    /** 二级意图置信度 */
    private Double taskConfidence;

    /** 二级意图原因 */
    private String taskReason;

    /** 提取的实体列表 */
    private List<String> entities;

    /** 执行步数 */
    private int stepCount;

    /** 错误信息 */
    private String error;

    /** 步数 +1 */
    public void incrementStep() {
        this.stepCount++;
    }

    /** 转换为 RouterResult */
    public RouterResult toRouterResult() {
        return RouterResult.builder()
                .taskFamily(taskFamily)
                .retrievalMode(retrievalMode)
                .riskLevel(riskLevel)
                .needsClarification(needsClarification)
                .clarificationQuestion(clarificationQuestion)
                .params(params)
                .intentScope(intentScope)
                .intentConfidence(intentConfidence)
                .intentReason(intentReason)
                .taskConfidence(taskConfidence)
                .taskReason(taskReason)
                .entities(entities)
                .build();
    }
}
