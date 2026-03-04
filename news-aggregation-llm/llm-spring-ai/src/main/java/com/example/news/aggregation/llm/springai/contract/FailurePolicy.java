package com.example.news.aggregation.llm.springai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 失败处理策略。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailurePolicy {
    private List<String> fallbackTools;
    private boolean replanAllowed;
    private boolean needUserInputOnFailure;
    private ExecutionEnums.ResumeMode resumeMode;
}

