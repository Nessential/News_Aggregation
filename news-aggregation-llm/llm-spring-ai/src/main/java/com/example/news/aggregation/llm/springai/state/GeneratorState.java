package com.example.news.aggregation.llm.springai.state;

import com.example.news.aggregation.llm.springai.contract.GeneratorDraft;
import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GeneratorGraph状态
 * 记录生成过程的输入、草稿与质量信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratorState {

    /** 用户查询 */
    private String query;

    /** 任务类型 */
    private String taskFamily;

    /** 检索模式 */
    private String retrievalMode;

    /** 是否允许无证据直接回答 */
    private Boolean allowNoEvidence;

    /** 证据列表 */
    private List<RetrievalResult> evidence;

    /** 当前生成草稿 */
    private GeneratorDraft draft;

    /** 质量评分 */
    private Double qualityScore;

    /** 是否通过质量检查 */
    private Boolean validated;

    /** 重试次数 */
    private int retryCount;

    /** 最大重试次数 */
    private int maxRetries;

    /** 错误信息 */
    private String error;

    /** 执行步数 */
    private int stepCount;

    /** 步数+1 */
    public void incrementStep() {
        this.stepCount++;
    }
}
