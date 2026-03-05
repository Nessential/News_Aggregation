package com.example.news.aggregation.agent.workflow.validation;

import com.example.news.aggregation.llm.springai.contract.ExecutionEnums;
import lombok.Getter;

/**
 * 工具输入/输出校验异常。
 * 通过 errorCategory 将校验失败分类映射到决策表动作。
 */
@Getter
public class ToolValidationException extends RuntimeException {

    private final ExecutionEnums.ErrorCategory errorCategory;
    private final String reasonCode;

    public ToolValidationException(ExecutionEnums.ErrorCategory errorCategory,
                                   String reasonCode,
                                   String message) {
        super(message);
        this.errorCategory = errorCategory;
        this.reasonCode = reasonCode;
    }
}

