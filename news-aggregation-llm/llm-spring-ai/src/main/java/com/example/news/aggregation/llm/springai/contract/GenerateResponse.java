package com.example.news.aggregation.llm.springai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM生成响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateResponse {

    /** 生成内容 */
    private String content;

    /** Token使用量（可选） */
    private Integer usageTokens;
}