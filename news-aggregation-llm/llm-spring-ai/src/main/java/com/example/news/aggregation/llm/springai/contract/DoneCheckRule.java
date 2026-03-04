package com.example.news.aggregation.llm.springai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 结果完成判定规则。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoneCheckRule {
    private List<String> requiredFields;
    private Integer minEvidenceCount;
    private String expression;
}

