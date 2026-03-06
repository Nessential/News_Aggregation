package com.example.news.aggregation.llm.springai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Router结果契约
 * 包含意图识别与参数抽取结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouterResult {

    /** 任务类型：QA、SUMMARY、COMPARE、TIMELINE、DEEP_DIVE */
    private String taskFamily;

    /** 检索模式：SEMANTIC、KEYWORD、HYBRID、NONE */
    private String retrievalMode;

    /** 风险级别：LOW、MEDIUM、HIGH */
    private String riskLevel;

    /** 是否需要澄清 */
    private Boolean needsClarification;

    /** 澄清问题 */
    private String clarificationQuestion;

    /** 提取的参数（时间/分类/语言等） */
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

    /** 提取的实体列表（国家/公司/人物/事件等） */
    private List<String> entities;

    /**
     * 创建默认QA结果（兜底）
     * 当Router失败时返回此结果
     */
    public static RouterResult defaultQA() {
        return RouterResult.builder()
                .taskFamily("QA")
                .retrievalMode("HYBRID")
                .riskLevel("LOW")
                .needsClarification(false)
                .intentScope("NEWS")
                .intentConfidence(0.0)
                .intentReason("default")
                .build();
    }
}
