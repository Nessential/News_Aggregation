package com.example.news.aggregation.agent.workflow;

import com.example.news.aggregation.llm.springai.contract.DoneCheckRule;
import com.example.news.aggregation.llm.springai.contract.FailurePolicy;
import com.example.news.aggregation.llm.springai.contract.RetryPolicy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 工作流步骤。
 * 绑定能力名称与参数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowStep {

    /** 步骤 ID */
    private String stepId;

    /** 能力名称 */
    private String capabilityName;

    /** 依赖的步骤 ID 列表 */
    private List<String> dependsOn;

    /** 步骤参数 */
    private Map<String, Object> parameters;

    /** 副作用标签（NONE/READ/WRITE/EXTERNAL） */
    private String sideEffect;

    /** 完成判定规则引用 */
    private String doneCheckRef;

    /** 输出 Schema（来自 ExecutionStep.outputSchema） */
    private Map<String, Object> outputSchema;

    /** doneCheck 规则对象（统一语义定义，不再拆平字段） */
    private DoneCheckRule doneCheck;

    /** Schema 版本（来自 ExecutionPlan.schemaVersion） */
    private String schemaVersion;

    /** 语义版本（来自 ExecutionPlan.semanticVersion） */
    private String semanticVersion;

    /** 重试策略（来自 ExecutionStep.retryPolicy） */
    private RetryPolicy retryPolicy;

    /** 失败策略（来自 ExecutionStep.failurePolicy） */
    private FailurePolicy failurePolicy;
}
