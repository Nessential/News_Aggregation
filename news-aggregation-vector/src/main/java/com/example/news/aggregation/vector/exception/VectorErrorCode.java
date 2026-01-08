package com.example.news.aggregation.vector.exception;

import com.example.news.aggregation.base.exception.ErrorCode;

/**
 * 向量数据库错误码枚举
 * 定义向量操作相关的所有错误类型和错误信息
 * 
 * @author system
 */
public enum VectorErrorCode implements ErrorCode {
    
    /**
     * 向量数据库连接失败
     */
    CONNECTION_FAILED("VECTOR_001", "向量数据库连接失败"),
    
    /**
     * 集合不存在
     */
    COLLECTION_NOT_FOUND("VECTOR_002", "集合不存在"),
    
    /**
     * 创建集合失败
     */
    COLLECTION_CREATE_FAILED("VECTOR_003", "创建集合失败"),
    
    /**
     * 向量插入/更新失败
     */
    UPSERT_FAILED("VECTOR_004", "向量插入/更新失败"),
    
    /**
     * 向量搜索失败
     */
    SEARCH_FAILED("VECTOR_005", "向量搜索失败"),
    
    /**
     * 向量删除失败
     */
    DELETE_FAILED("VECTOR_006", "向量删除失败"),
    
    /**
     * 无效的向量数据
     */
    INVALID_VECTOR("VECTOR_007", "无效的向量数据");

    private final String code;
    private final String message;

    VectorErrorCode(String code, String message) {
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