package com.example.news.aggregation.llm.springai.decision;

import com.example.news.aggregation.llm.springai.contract.ExecutionEnums;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 决策输入上下文。
 * Week5 扩展了 Replan 预算、硬边界、证据门控和变化有效性字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailureContext {
    private ExecutionEnums.ErrorCategory errorCategory;
    /** 失败细分原因码，用于细粒度分流。 */
    private String failureReasonCode;
    private Integer retryCount;
    private Integer maxRetries;
    private boolean hasFallbackTool;
    private boolean replanAllowed;
    /** 是否启用全局 Replan 功能（Week6 灰度开关）。null 视为启用。 */
    private Boolean replanFeatureEnabled;
    private boolean needsExternalSignal;
    private ExecutionEnums.SideEffectType sideEffect;
    private ExecutionEnums.EffectState effectState;
    private ExecutionEnums.ResumeMode preferredResumeMode;

    /** run 级重规划计数与预算上限 */
    private Integer replanCountRun;
    private Integer maxReplansPerRun;
    /** step 级重规划计数与预算上限 */
    private Integer replanCountStep;
    private Integer maxReplansPerStep;

    /** Replan 硬边界：步骤数量与超时限制 */
    private Integer stepCount;
    private Integer maxSteps;
    private Long elapsedMs;
    private Long timeoutMs;

    /** 证据是否达标。null 表示未启用门控或未参与本次决策。 */
    private Boolean evidenceSufficient;
    /** 是否检测到“重规划无有效变化”。 */
    private Boolean replanNoEffectiveChange;
    /** 是否命中不可重试原因。 */
    private Boolean replanNonRetryableReason;
}
