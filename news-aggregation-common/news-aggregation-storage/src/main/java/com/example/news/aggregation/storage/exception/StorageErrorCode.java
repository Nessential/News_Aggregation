package com.example.news.aggregation.storage.exception;

import com.example.news.aggregation.base.exception.ErrorCode;

public enum StorageErrorCode implements ErrorCode {
    UPLOAD_FAILED("STORAGE_001", "文件上传失败"),
    DOWNLOAD_FAILED("STORAGE_002", "文件下载失败"),
    BUCKET_NOT_FOUND("STORAGE_003", "存储桶不存在"),
    FILE_NOT_FOUND("STORAGE_004", "文件不存在"),
    INVALID_URL("STORAGE_005", "无效的文件URL"),
    CONNECTION_FAILED("STORAGE_006", "存储服务连接失败");


    private final String code;
    private final String message;

    StorageErrorCode(String code, String message) {
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
