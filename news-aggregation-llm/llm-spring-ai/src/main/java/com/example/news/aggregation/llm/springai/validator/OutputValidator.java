package com.example.news.aggregation.llm.springai.validator;

import com.example.news.aggregation.llm.springai.contract.ExecutionPlan;
import com.example.news.aggregation.llm.springai.contract.ExecutionStep;
import com.example.news.aggregation.llm.springai.contract.GeneratorDraft;
import com.example.news.aggregation.llm.springai.contract.RouterResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class OutputValidator {

    public boolean validate(GeneratorDraft draft) {
        if (draft == null) {
            log.warn("Validation failed: draft is null");
            return false;
        }

        List<String> errors = new ArrayList<>();
        List<GeneratorDraft.AnswerItem> items = draft.getAnswerItems();

        if (items == null || items.isEmpty()) {
            errors.add("answerItems is empty");
        } else {
            boolean hasNonBlankText = false;
            for (GeneratorDraft.AnswerItem item : items) {
                if (item != null && item.getText() != null && !item.getText().isBlank()) {
                    hasNonBlankText = true;
                    int len = item.getText().length();
                    if (len > 10000) {
                        errors.add("Answer item too long (> 10000 characters)");
                        break;
                    }
                    if (containsDangerousContent(item.getText())) {
                        errors.add("Answer item contains potentially dangerous content");
                        break;
                    }
                }
            }
            if (!hasNonBlankText) {
                errors.add("All answerItems.text are empty");
            }
        }

        if (draft.getQualityScore() == null || draft.getQualityScore() < 0.1) {
            errors.add("Quality score too low: " + draft.getQualityScore());
        }

        if (!errors.isEmpty()) {
            log.warn("Validation failed with {} errors: {}", errors.size(), errors);
            return false;
        }

        return true;
    }

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

    private boolean containsDangerousContent(String text) {
        if (text == null) {
            return false;
        }
        String lowerText = text.toLowerCase();
        String[] dangerousKeywords = {"暴力", "血腥", "色情", "赌博", "诈骗"};
        for (String keyword : dangerousKeywords) {
            if (lowerText.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public ValidationResult validateWithDetails(GeneratorDraft draft) {
        boolean isValid = validate(draft);
        ValidationResult result = new ValidationResult();
        result.setValid(isValid);
        result.setMessage(isValid ? "Validation passed" : "Validation failed");
        return result;
    }

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
