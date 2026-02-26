package com.example.news.aggregation.llm.springai.contract;

import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Generator请求
 * 用于Graph生成答案草稿
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratorRequest {

    /** 用户查询 */
    private String query;

    /** 任务类型 */
    private String taskFamily;

    /** 检索模式：SEMANTIC、KEYWORD、HYBRID、NONE */
    private String retrievalMode;

    /** 证据列表 */
    private List<RetrievalResult> evidence;
}
