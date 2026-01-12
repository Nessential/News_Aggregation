package com.example.news.aggregation.es.exception;

import com.example.news.aggregation.base.exception.ErrorCode;

/**
 * Elasticsearch 错误码枚举
 * 定义 ES 操作相关的所有错误类型和错误信息
 */
public enum EsErrorCode implements ErrorCode {

    /**
     * ES 连接失败
     */
    CONNECTION_FAILED("ES_001", "Elasticsearch 连接失败"),

    /**
     * 索引不存在
     */
    INDEX_NOT_FOUND("ES_002", "索引不存在"),

    /**
     * 创建索引失败
     */
    INDEX_CREATE_FAILED("ES_003", "创建索引失败"),

    /**
     * 文档索引失败
     */
    INDEX_DOCUMENT_FAILED("ES_004", "文档索引失败"),

    /**
     * 文档删除失败
     */
    DELETE_DOCUMENT_FAILED("ES_005", "文档删除失败"),

    /**
     * 批量索引失败
     */
    BULK_INDEX_FAILED("ES_006", "批量索引失败"),

    /**
     * 搜索查询失败
     */
    SEARCH_FAILED("ES_007", "搜索查询失败"),

    /**
     * 聚合查询失败
     */
    AGGREGATION_FAILED("ES_008", "聚合查询失败");

    private final String code;
    private final String message;

    EsErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
