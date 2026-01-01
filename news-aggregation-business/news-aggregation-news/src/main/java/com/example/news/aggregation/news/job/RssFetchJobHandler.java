package com.example.news.aggregation.news.job;


import com.example.news.aggregation.job.handler.BaseJobHandler;
import com.example.news.aggregation.news.exception.NewsException;
import com.example.news.aggregation.news.service.RssFetchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RssFetchJobHandler extends BaseJobHandler {

    private final RssFetchService rssFetchService;


    @Override
    public void execute(String param) throws Exception {
        log.info("开始执行 RSS 抓取任务，参数: {}", param);
        try{
            rssFetchService.fetchAndSaveNews();
            log.info("RSS 抓取任务执行完成");
        }
        catch (Exception e){
            log.error("RSS 抓取任务执行失败", e);
            throw e;
        }


    }


}
