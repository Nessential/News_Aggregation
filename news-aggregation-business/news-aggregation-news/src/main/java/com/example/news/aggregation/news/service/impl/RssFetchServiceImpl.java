package com.example.news.aggregation.news.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.news.aggregation.news.config.RssSourceProperties;
import com.example.news.aggregation.news.domain.entity.News;
import com.example.news.aggregation.news.domain.entity.NewsCategory;
import com.example.news.aggregation.news.exception.NewsException;
import com.example.news.aggregation.news.infrastructure.content.ContentExtractor;
import com.example.news.aggregation.news.infrastructure.mapper.NewsCategoryMapper;
import com.example.news.aggregation.news.infrastructure.mapper.NewsMapper;
import com.example.news.aggregation.news.infrastructure.rss.RssParser;
import com.example.news.aggregation.news.service.RssFetchService;
import com.example.news.aggregation.storage.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
@Slf4j
@RequiredArgsConstructor
public class RssFetchServiceImpl implements RssFetchService {

    private final RssSourceProperties rssSourceProperties;
    private final RssParser rssParser;
    private final NewsMapper newsMapper;
    private final NewsCategoryMapper newsCategoryMapper;
    private final ContentExtractor contentExtractor;

    @Autowired
    private StorageService storageService;

    @Override
    public void fetchAndSaveNews() {
        List<RssSourceProperties.RssSource> sources = rssSourceProperties.getSources();
        if (sources == null || sources.isEmpty()) {
            log.warn("未配置 RSS 源");
            return;
        }

        int totalSaved = 0;
        int totalFailed = 0;

        for (RssSourceProperties.RssSource source : sources) {
            try {
                int saved = fetchSingleSource(source);
                totalSaved += saved;
            } catch (NewsException e) {
                log.error("抓取 RSS 源失败: {}, errorCode={}, message={}",
                        source.getName(), e.getErrorCode().getCode(), e.getMessage());
                totalFailed++;
            }
        }

        log.info("RSS 抓取完成，成功保存 {} 条新闻，失败 {} 个源", totalSaved, totalFailed);
    }

    @Override
    public void selectForTranslate() {
        // 保留空实现
    }

    private int fetchSingleSource(RssSourceProperties.RssSource source) {
        log.info("开始抓取 RSS 源: {}", source.getName());
        List<News> newsList = rssParser.parse(source.getUrl(), source.getName(), source.getCategory());

        Long categoryId = resolveCategoryId(source.getCategory());
        int saved = 0;

        for (News news : newsList) {
            try {
                // URL 命中过滤关键词时直接跳过（不抓正文、不入库）
                if (shouldSkipByUrl(news.getLink())) {
                    log.info("URL 命中过滤关键词，跳过抓取: {}", news.getLink());
                    continue;
                }

                // 去重
                News existed = newsMapper.selectByLink(news.getLink());
                if (existed != null) {
                    log.debug("新闻已存在，跳过: {}", news.getLink());
                    continue;
                }

                news.setCategory_id(categoryId);
                news.setCategory(source.getCategory());

                // 保存图片
                String originImageUrl = news.getImage_url();
                String finalImageUrl = storageService.uploadFromUrl(originImageUrl, source.getName(), news.getTitle());
                news.setImage_url(finalImageUrl);

                // 抓取正文
                fetchNewsContent(news);

                // 初始化翻译状态
                news.setTranslation_status(0);
                newsMapper.insert(news);
                saved++;
            } catch (Exception e) {
                log.error("保存新闻失败: {}", news.getLink(), e);
            }
        }

        log.info("RSS 源 {} 抓取完成，保存 {} 条新闻", source.getName(), saved);
        return saved;
    }

    /**
     * 解析分类 ID，不存在时自动创建分类。
     */
    private Long resolveCategoryId(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) {
            return null;
        }

        NewsCategory existed = newsCategoryMapper.selectByName(categoryName);
        if (existed != null) {
            return existed.getId();
        }

        try {
            NewsCategory category = new NewsCategory();
            category.setName(categoryName);
            newsCategoryMapper.insert(category);
            log.info("自动创建新闻分类成功: name={}, id={}", categoryName, category.getId());
            return category.getId();
        } catch (DuplicateKeyException ex) {
            // 并发场景下可能由其他线程先创建，回查即可
            NewsCategory category = newsCategoryMapper.selectOne(
                    Wrappers.<NewsCategory>lambdaQuery()
                            .eq(NewsCategory::getName, categoryName)
                            .last("limit 1")
            );
            if (category != null) {
                return category.getId();
            }
            throw ex;
        }
    }

    /**
     * 抓取新闻正文内容。
     */
    private void fetchNewsContent(News news) {
        try {
            String content = contentExtractor.extractContent(news.getLink());
            if (content != null && !content.isEmpty()) {
                news.setContext(content);
                news.setContent_status(1);
                log.debug("正文抓取成功: {}", news.getLink());
            } else {
                news.setContent_status(2);
                log.warn("正文抓取为空: {}", news.getLink());
            }
        } catch (Exception e) {
            news.setContent_status(2);
            log.error("正文抓取异常: {}", news.getLink(), e);
        }
    }

    private boolean shouldSkipByUrl(String link) {
        if (link == null || link.isBlank()) {
            return false;
        }

        List<String> keywords = rssSourceProperties.getSkipUrlKeywords();
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }

        String lowerLink = link.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            if (lowerLink.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}