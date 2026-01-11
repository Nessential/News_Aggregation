package com.example.news.aggregation.news.job;

import com.example.news.aggregation.news.service.NewsVectorService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NewsVectorJobHandler {

    private final NewsVectorService newsVectorService;
    private static final int DEFAULT_BATCH_SIZE = 20;

    @XxlJob("newsVectorJob")
    public void execute() {
        String param = XxlJobHelper.getJobParam();
        int batchSize = parseBatchSize(param);

        log.info("开始执行新闻向量化任务，批次大小: {}", batchSize);

        try {
            int success = newsVectorService.vectorizePendingNews(batchSize);

            String message = String.format("向量化完成: 成功=%d", success);
            log.info("向量化完成: 成功{}", success);
            XxlJobHelper.handleSuccess(message);

        } catch (Exception e) {
            log.error("向量化任务执行失败", e);
            XxlJobHelper.handleFail("任务执行失败: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private int parseBatchSize(String param) {
        if (param == null || param.trim().isEmpty()) {
            return DEFAULT_BATCH_SIZE;
        }
        try {
            return Integer.parseInt(param.trim());
        } catch (NumberFormatException e) {
            log.warn("参数格式错误，使用默认值: {}", DEFAULT_BATCH_SIZE);
            return DEFAULT_BATCH_SIZE;
        }
    }
}