package com.example.news.aggregation.llm.springai.prompt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一提示词仓库（按 key 加载并缓存）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptRegistry {

    private final ResourceLoader resourceLoader;
    private final PromptProperties properties;

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 读取提示词内容
     */
    public String getPrompt(String key, String fallback) {
        log.info("获取提示词，key={}",key);
        if (key == null || key.isBlank()) {
            return fallback;
        }
        return cache.computeIfAbsent(key, k -> loadPrompt(k, fallback));
    }

    private String loadPrompt(String key, String fallback) {
        String basePath = properties.getBasePath();
        String suffix = properties.getSuffix();
        String path = basePath + key + suffix;
        try {
            Resource resource = resourceLoader.getResource(path);
            if (!resource.exists()) {
                log.warn("提示词文件不存在，使用fallback: path={}", path);
                return fallback;
            }
            try (InputStream inputStream = resource.getInputStream()) {
                byte[] bytes = inputStream.readAllBytes();
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("提示词加载失败，使用fallback: path={}, error={}", path, e.getMessage());
            return fallback;
        }
    }
}
