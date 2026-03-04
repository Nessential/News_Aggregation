package com.example.news.aggregation.agent.workflow;

import com.example.news.aggregation.llm.springai.contract.ExecutionEnums;
import com.example.news.aggregation.llm.springai.contract.ExecutionStep;
import org.springframework.stereotype.Component;

/**
 * 执行步骤语义映射器。
 */
@Component
public class StepSemanticMapper {

    /**
     * 解析步骤副作用语义。
     * 当 Planner 未显式提供副作用时，统一按 NONE 处理，避免执行层出现空值分支。
     */
    public String resolveSideEffect(ExecutionStep step) {
        if (step == null || step.getSideEffect() == null) {
            return ExecutionEnums.SideEffectType.NONE.name();
        }
        return step.getSideEffect().name();
    }

    /**
     * 解析完成判定规则引用。
     * 优先使用 doneCheck.expression；未提供时回退到 required_fields。
     */
    public String resolveDoneCheckRef(ExecutionStep step) {
        if (step == null || step.getDoneCheck() == null) {
            return "";
        }
        if (step.getDoneCheck().getExpression() != null && !step.getDoneCheck().getExpression().isBlank()) {
            return step.getDoneCheck().getExpression();
        }
        return "required_fields";
    }
}
