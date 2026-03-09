package com.example.news.aggregation.storage.service.impl;

import com.example.news.aggregation.storage.service.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * No-op 存储实现。
 * 当 MinIO 被禁用时使用，避免启动失败。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "storage.minio", name = "enabled", havingValue = "false")
public class NoopStorageService implements StorageService {

    @Override
    public String uploadFromUrl(String sourceUrl, String folder, String fileName) {
        log.warn("MinIO disabled, skip uploadFromUrl. sourceUrl={}", sourceUrl);
        return sourceUrl;
    }

    @Override
    public String upload(InputStream inputStream, String folder, String fileName, String contentType) {
        log.warn("MinIO disabled, skip upload. fileName={}", fileName);
        return "";
    }

    @Override
    public boolean delete(String objectPath) {
        log.warn("MinIO disabled, skip delete. objectPath={}", objectPath);
        return false;
    }

    @Override
    public boolean exists(String objectPath) {
        return false;
    }

    @Override
    public String getAccessUrl(String path) {
        // Noop模式下直接返回原路径
        return path;
    }
}
