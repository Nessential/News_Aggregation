package com.example.news.aggregation.news.job;

import com.example.news.aggregation.news.service.TranslationService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TranslationJobHandler {

    private final TranslationService translationService;

    private static final int DEFAULT_BATCH_SIZE = 10;

    @XxlJob("translationJobHandler")
    public void execute() {
        String param = XxlJobHelper.getJobParam();
        log.info("开始执行翻译任务，参数: {}", param);

        int batchSize = DEFAULT_BATCH_SIZE;
        if (param != null && !param.isEmpty()) {
            try {
                batchSize = Integer.parseInt(param.trim());
            } catch (NumberFormatException e) {
                log.warn("参数格式错误，使用默认批次大小: {}", DEFAULT_BATCH_SIZE);
            }
        }

        try {
            translationService.translatePendingNews(batchSize);
            log.info("翻译任务执行完成");
            XxlJobHelper.handleSuccess("翻译任务执行成功");
        } catch (Exception e) {
            log.error("翻译任务执行失败", e);
            XxlJobHelper.handleFail("翻译任务执行失败: " + e.getMessage());
        }
    }
}