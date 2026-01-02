package com.example.news.aggregation.news.service.impl;

import com.example.news.aggregation.news.config.RssSourceProperties;
import com.example.news.aggregation.news.domain.entity.News;
import com.example.news.aggregation.news.exception.NewsException;
import com.example.news.aggregation.news.infrastructure.mapper.NewsMapper;
import com.example.news.aggregation.news.infrastructure.rss.RssParser;
import com.example.news.aggregation.news.service.RssFetchService;
import com.example.news.aggregation.storage.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class RssFetchServiceImpl implements RssFetchService {


    private final RssSourceProperties  rssSourceProperties;
    private final RssParser rssParser;
    private final NewsMapper newsMapper;

    @Autowired
    private StorageService storageService;

    @Override
    public void fetchAndSaveNews() {
        List<RssSourceProperties.RssSource> sources  = rssSourceProperties.getSources();

        if(sources==null || sources.isEmpty()){
            log.warn("未配置任何 RSS 源");
            return;
        }

        int totalSaved = 0;
        int totalFailed = 0;

        for(RssSourceProperties.RssSource source : sources){
            try {
                int saved  = fetchSingleSource(source);
                totalSaved += saved;
            }
            catch (NewsException e){
                log.error("抓取Rss源失败:{},errorCode={},message={}",
                        source.getName(),e.getErrorCode().getCode(),e.getMessage());
                totalFailed++;
            }
        }

        log.info("Rss抓取完成，成功保存{}条新闻，失败{}个源",totalSaved,totalFailed);

    }


    private int fetchSingleSource(RssSourceProperties.RssSource source){
        log.info("开始抓取Rss源:{}",source.getName());
        List<News> newsList = rssParser.parse(source.getUrl(),source.getName());

        int saved = 0;

        for (News news : newsList){

            try{
//                去重检查
                News existed = newsMapper.selectByLink(news.getLink());
                if(existed!=null) {
                    log.debug("新闻已存在，跳过:{}",news.getLink());
                    continue;
                }
                else {
//                    保存图片
                    String originImageUrl = news.getImage_url();
                    String finalImageUrl = storageService.uploadFromUrl(originImageUrl,source.getName(),news.getTitle());
                    news.setImage_url(finalImageUrl);

                    newsMapper.insert(news);
                    saved++;
                }
            } catch (Exception e) {
                // 单条新闻保存失败，记录日志，继续下一条
                log.error("保存新闻失败: {}", news.getLink(), e);
            }

        }
        log.info("RSS 源 {} 抓取完成，保存 {} 条新闻", source.getName(), saved);

        return saved;
    }
}

