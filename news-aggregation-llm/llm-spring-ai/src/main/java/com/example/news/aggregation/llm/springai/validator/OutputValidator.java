package com.example.news.aggregation.llm.springai.validator;

import com.example.news.aggregation.llm.springai.contract.GeneratorDraft;
import com.example.news.aggregation.llm.springai.contract.ExecutionPlan;
import com.example.news.aggregation.llm.springai.contract.ExecutionStep;
import com.example.news.aggregation.llm.springai.contract.RouterResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 输出校验器
 * 校验LLM生成的答案质量
 */
@Slf4j
@Component
public class OutputValidator {

    /**
     * 校验生成的答案
     *
     * @param draft 生成草稿
     * @return 校验是否通过
     */
    public boolean validate(GeneratorDraft draft) {
        if (draft == null) {
            log.warn("Validation failed: draft is null");
            return false;
        }

        List<String> errors = new ArrayList<>();

        // 1. 检查答案是否为空

        if (draft.getAnswer() == null || draft.getAnswer().trim().isEmpty()) {
            errors.add("Answer is empty");
        }

        // 2. 检查答案长度（过短或过长）

        if (draft.getAnswer() != null) {
            int length = draft.getAnswer().length();
            if (length < 10) {
                errors.add("Answer too short (< 10 characters)");
            } else if (length > 10000) {
                errors.add("Answer too long (> 10000 characters)");
            }
        }

        // 3. 检查质量评分

        if (draft.getQualityScore() == null || draft.getQualityScore() < 0.3) {
            errors.add("Quality score too low: " + draft.getQualityScore());
        }

        // 4. 检查是否包含引用（可选）

        if (draft.getCitations() == null || draft.getCitations().isEmpty()) {
            log.debug("No citations provided (may be acceptable for some queries)");
        }

        // 5. 检查危险内容（简单关键词检测）

        if (containsDangerousContent(draft.getAnswer())) {
            errors.add("Answer contains potentially dangerous content");
        }

        if (!errors.isEmpty()) {
            log.warn("Validation failed with {} errors: {}", errors.size(), errors);
            return false;
        }

        log.info("Validation passed for draft with quality score: {}", draft.getQualityScore());
        return true;
    }

    /**
     * 校验Router结果
     *
     * @param result Router结果
     * @return 是否通过
     */
    public boolean validateRouter(RouterResult result) {
        if (result == null) {
            log.warn("RouterResult is null");
            return false;
        }
        if (result.getTaskFamily() == null || result.getTaskFamily().isBlank()) {
            log.warn("RouterResult.taskFamily is empty");
            return false;
        }
        if (result.getRetrievalMode() == null || result.getRetrievalMode().isBlank()) {
            log.warn("RouterResult.retrievalMode is empty");
            return false;
        }
        return true;
    }

    /**
     * 校验Plan结果
     *
     * @param plan 计划
     * @return 是否通过
     */
    public boolean validateExecutionPlan(ExecutionPlan plan) {
        if (plan == null) {
            log.warn("ExecutionPlan is null");
            return false;
        }
        if (plan.getSteps() == null || plan.getSteps().isEmpty()) {
            log.warn("ExecutionPlan steps is empty");
            return false;
        }
        for (ExecutionStep step : plan.getSteps()) {
            if (step == null || step.getStepId() == null || step.getStepId().isBlank()) {
                log.warn("ExecutionPlan stepId is empty");
                return false;
            }
            if (step.getTool() == null || step.getTool().isBlank()) {
                log.warn("ExecutionPlan tool is empty, stepId={}", step.getStepId());
                return false;
            }
        }
        return true;
    }

    /**
     * 检查是否包含危险内容
     * MVP实现：简单关键词匹配
     * 生产环境：应使用专业的内容安全API
     */
    private boolean containsDangerousContent(String text) {
        if (text == null) {
            return false;
        }

        String lowerText = text.toLowerCase();

        // 简单的危险关键词列表（示例）
        String[] dangerousKeywords = {
            "暴力",
            "血腥",
            "色情",
            "赌博",
            "诈骗"
        };

        for (String keyword : dangerousKeywords) {
            if (lowerText.contains(keyword)) {
                log.warn("Detected dangerous keyword: {}", keyword);
                return true;
            }
        }

        return false;
    }

    /**
     * 校验答案并返回详细信息
     *
     * @param draft 生成草稿
     * @return 校验结果（包含错误信息）
     */
    public ValidationResult validateWithDetails(GeneratorDraft draft) {
        boolean isValid = validate(draft);

        ValidationResult result = new ValidationResult();
        result.setValid(isValid);

        if (!isValid) {
            result.setMessage("Validation failed");
        } else {
            result.setMessage("Validation passed");
        }

        return result;
    }

    /**
     * 校验结果类
     */
    public static class ValidationResult {
        private boolean valid;
        private String message;

        public boolean isValid() {
            return valid;
        }

        public void setValid(boolean valid) {
            this.valid = valid;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
