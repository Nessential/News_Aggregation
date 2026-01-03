package com.example.news.aggregation.news.service.impl;

import com.example.news.aggregation.base.util.translate.BaiduTranslateClient;
import com.example.news.aggregation.news.domain.entity.News;
import com.example.news.aggregation.news.infrastructure.mapper.NewsMapper;
import com.example.news.aggregation.news.service.TranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 翻译服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranslationServiceImpl implements TranslationService {

    private final NewsMapper newsMapper;
    private final BaiduTranslateClient translateClient;

    // 百度翻译API QPS限制，免费版1次/秒
    private static final long TRANSLATE_INTERVAL_MS = 1100;

    @Override
    public void translatePendingNews(int batchSize) {
        List<News> newsList = newsMapper.selectForTranslation(batchSize);
        
        if (newsList.isEmpty()) {
            log.info("没有待翻译的新闻");
            return;
        }

        log.info("开始翻译 {} 条新闻", newsList.size());
        int success = 0;
        int failed = 0;

        for (News news : newsList) {
            try {
                boolean result = translateSingleNews(news);
                if (result) {
                    success++;
                } else {
                    failed++;
                }
                
                // 控制请求频率
                Thread.sleep(TRANSLATE_INTERVAL_MS);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("翻译任务被中断");
                break;
            } catch (Exception e) {
                log.error("翻译新闻失败: id={}", news.getId(), e);
                failed++;
            }
        }

        log.info("翻译任务完成，成功: {}, 失败: {}", success, failed);
    }

    /**
     * 翻译单条新闻
     */
    private boolean translateSingleNews(News news) {
        try {
            // 翻译标题
            String titleCn = translateClient.translate(news.getTitle());
            if (titleCn != null) {
                news.setTitle_cn(titleCn);
            }

            // 翻译摘要
            if (news.getSummary() != null && !news.getSummary().isEmpty()) {
                Thread.sleep(TRANSLATE_INTERVAL_MS);
                String summaryCn = translateClient.translate(news.getSummary());
                if (summaryCn != null) {
                    news.setSummary_cn(summaryCn);
                }
            }

            // 翻译正文（如果正文过长，截取前5000字符）
            if (news.getContext() != null && !news.getContext().isEmpty()) {
                Thread.sleep(TRANSLATE_INTERVAL_MS);
                String context = news.getContext();
                if (context.length() > 5000) {
                    context = context.substring(0, 5000);
                }
                String contextCn = translateClient.translate(context);
                if (contextCn != null) {
                    news.setContext_cn(contextCn);
                }
            }

            // 更新状态
            news.setTranslation_status(1);
            newsMapper.updateById(news);
            
            log.debug("翻译成功: id={}, title={}", news.getId(), news.getTitle());
            return true;

        } catch (Exception e) {
            log.error("翻译单条新闻失败: id={}", news.getId(), e);
            news.setTranslation_status(2);
            newsMapper.updateById(news);
            return false;
        }
    }
}