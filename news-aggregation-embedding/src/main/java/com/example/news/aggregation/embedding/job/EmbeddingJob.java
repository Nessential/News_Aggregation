package com.example.news.aggregation.embedding.job;

import com.example.news.aggregation.embedding.service.EmbeddingService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class EmbeddingJob {

    private final EmbeddingService embeddingService;

    @XxlJob("EmbeddingJob")
    public void Execute(){
        try{
            log.info("向量化测试开始");
            embeddingService.embed("向量化测试");
        } catch (Exception e) {
            log.error("向量化失败",e);
            throw new RuntimeException(e);
        }
    }
}
