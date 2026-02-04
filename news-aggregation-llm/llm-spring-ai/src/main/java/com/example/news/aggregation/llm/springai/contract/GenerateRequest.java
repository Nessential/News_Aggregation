package com.example.news.aggregation.llm.springai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 生成请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateRequest {

    /** 提示词 */
    private String prompt;

    /** 模型名称（可选） */
    private String model;

    /** 温度（可选） */
    private Double temperature;
}
