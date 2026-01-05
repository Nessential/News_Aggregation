package com.example.news.aggregation.news.job;


import com.example.news.aggregation.news.service.RssFetchService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RssFetchJobHandler  {

    private final RssFetchService rssFetchService;


    @XxlJob("rssFetchJobHandler")
    public void execute() throws Exception {
        String param = XxlJobHelper.getJobParam();  // 2. 获取任务参数（admin后台配置的）
        log.info("开始执行 RSS 抓取任务，参数: {}", param);
        try{

            rssFetchService.fetchAndSaveNews();
            log.info("RSS 抓取任务执行完成");
            // 4. 标记任务成功
            XxlJobHelper.handleSuccess("RSS抓取任务执行成功");
        }
        catch (Exception e){
            log.error("RSS 抓取任务执行失败", e);
            XxlJobHelper.handleFail("Rss抓取失败");
            throw e;
        }


    }


}
