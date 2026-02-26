package com.example.news.aggregation.agent.domain;

import com.example.news.aggregation.agent.enums.RetrievalMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 检索尝试记录。
 * 跟踪每次检索的参数和结果，用于调试和优化。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalAttempt implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 尝试序号 */
    private Integer attemptNumber;

    /** 检索模式 */
    private RetrievalMode mode;

    /** 检索 query */
    private String query;

    /** 检索到的文档数量 */
    private Integer resultCount;

    /** 执行时间戳 */
    private LocalDateTime timestamp;

    /** 执行耗时（毫秒） */
    private Long durationMs;

    /** 是否成功 */
    private Boolean success;

    /** 错误信息（如果失败） */
    private String errorMessage;
}