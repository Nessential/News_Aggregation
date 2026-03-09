package com.example.news.aggregation.news.controller;

import com.example.news.aggregation.news.domain.entity.News;
import com.example.news.aggregation.news.dto.ArticlesResponse;
import com.example.news.aggregation.news.dto.IdsRequest;
import com.example.news.aggregation.news.dto.NewsArticleDto;
import com.example.news.aggregation.news.dto.NewsDetailDto;
import com.example.news.aggregation.news.dto.NewsListItemDto;
import com.example.news.aggregation.news.dto.NewsListResponse;
import com.example.news.aggregation.news.infrastructure.mapper.NewsMapper;
import com.example.news.aggregation.storage.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 新闻查询控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/news/articles")
@RequiredArgsConstructor
public class NewsQueryController {

    private final NewsMapper newsMapper;
    private final StorageService storageService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    /**
     * 首页新闻列表
     */
    @GetMapping
    public ResponseEntity<NewsListResponse> list(

            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String lang,
            @RequestParam(defaultValue = "false") boolean includeAltLang,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String category
    ) {
        boolean preferZh = isPreferZh(lang);
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(pageSize, 1);
        long offset = (long) (safePage - 1) * safePageSize;

        List<News> records = newsMapper.selectListPage(offset, safePageSize, keyword, source, category);
        Long total = newsMapper.countList(keyword, source, category);
        List<NewsListItemDto> items = records.stream()
                .map(news -> toListItemDto(news, preferZh, includeAltLang))
                .collect(Collectors.toList());

        return ResponseEntity.ok(NewsListResponse.builder()
                .total(total == null ? 0L : total)
                .page(safePage)
                .pageSize(safePageSize)
                .items(items)
                .build());
    }

    /**
     * 新闻详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<NewsDetailDto> detail(
            @PathVariable Long id,
            @RequestParam(required = false) String lang
    ) {
        News news = newsMapper.selectDetailById(id);
        if (news == null) {
            return ResponseEntity.notFound().build();
        }
        boolean preferZh = isPreferZh(lang);
        return ResponseEntity.ok(toDetailDto(news, preferZh));
    }

    /**
     * 批量查询文章详情
     */
    @PostMapping("/by-ids")
    public ResponseEntity<ArticlesResponse> getByIds(@RequestBody IdsRequest request) {
        if (request == null || request.getIds() == null || request.getIds().isEmpty()) {
            log.info("[DIAG][mysql][by-ids] empty request|idsCount=0");
            return ResponseEntity.ok(ArticlesResponse.builder().articles(Collections.emptyList()).build());
        }
        log.info("[DIAG][mysql][by-ids] start|idsCount={} |idsSample={}",
                request.getIds().size(), summarizeIds(request.getIds()));

        List<News> newsList = newsMapper.selectBatchIds(request.getIds());
        List<NewsArticleDto> articles = newsList.stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        log.info("[DIAG][mysql][by-ids] end|dbCount={} |articleCount={} |articleSample={}",
                newsList.size(), articles.size(), summarizeArticles(articles));

        return ResponseEntity.ok(ArticlesResponse.builder().articles(articles).build());
    }

    private String summarizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "[]";
        }
        return ids.stream()
                .limit(5)
                .map(String::valueOf)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String summarizeArticles(List<NewsArticleDto> articles) {
        if (articles == null || articles.isEmpty()) {
            return "[]";
        }
        return articles.stream()
                .limit(3)
                .map(item -> "{id=" + item.getId()
                        + ",title=\"" + truncate(item.getTitle(), 30)
                        + "\",source=\"" + truncate(item.getSource(), 20)
                        + "\",publishedAt=" + item.getPublishedAt() + "}")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private NewsArticleDto toDto(News news) {
        if (news == null) {
            return null;
        }
        String publishedAt = news.getPublication_time() != null
                ? DATE_FORMATTER.format(Instant.ofEpochMilli(news.getPublication_time()))
                : null;
        return NewsArticleDto.builder()
                .id(news.getId())
                .title(news.getTitle())
                .url(news.getLink())
                .content(news.getContext())
                .source(news.getSource())
                .publishedAt(publishedAt)
                .build();
    }

    private NewsListItemDto toListItemDto(News news, boolean preferZh, boolean includeAltLang) {
        if (news == null) {
            return null;
        }
        String title = pickText(preferZh, news.getTitle_cn(), news.getTitle());
        String summary = pickText(preferZh, news.getSummary_cn(), news.getSummary());
        String titleCn = includeAltLang ? news.getTitle_cn() : null;
        String summaryCn = includeAltLang ? news.getSummary_cn() : null;
        String titleEn = includeAltLang ? news.getTitle() : null;
        String summaryEn = includeAltLang ? news.getSummary() : null;
        String publishedAt = news.getPublication_time() != null
                ? DATE_FORMATTER.format(Instant.ofEpochMilli(news.getPublication_time()))
                : null;
        return NewsListItemDto.builder()
                .id(news.getId())
                .title(title)
                .summary(summary)
                .titleCn(titleCn)
                .summaryCn(summaryCn)
                .titleEn(titleEn)
                .summaryEn(summaryEn)
                .imageUrl(storageService.getAccessUrl(news.getImage_url()))
                .link(news.getLink())
                .source(news.getSource())
                .publishedAt(publishedAt)
                .publicationTime(news.getPublication_time())
                .build();
    }

    private NewsDetailDto toDetailDto(News news, boolean preferZh) {
        if (news == null) {
            return null;
        }
        String title = pickText(preferZh, news.getTitle_cn(), news.getTitle());
        String summary = pickText(preferZh, news.getSummary_cn(), news.getSummary());
        String content = pickText(preferZh, news.getContext_cn(), news.getContext());
        String titleCn = news.getTitle_cn();
        String summaryCn = news.getSummary_cn();
        String contentCn = news.getContext_cn();
        String titleEn = news.getTitle();
        String summaryEn = news.getSummary();
        String contentEn = news.getContext();
        String publishedAt = news.getPublication_time() != null
                ? DATE_FORMATTER.format(Instant.ofEpochMilli(news.getPublication_time()))
                : null;
        return NewsDetailDto.builder()
                .id(news.getId())
                .title(title)
                .summary(summary)
                .content(content)
                .titleCn(titleCn)
                .summaryCn(summaryCn)
                .contentCn(contentCn)
                .titleEn(titleEn)
                .summaryEn(summaryEn)
                .contentEn(contentEn)
                .imageUrl(storageService.getAccessUrl(news.getImage_url()))
                .link(news.getLink())
                .source(news.getSource())
                .publishedAt(publishedAt)
                .publicationTime(news.getPublication_time())
                .build();
    }

    private boolean isPreferZh(String lang) {
        if (lang == null) {
            return false;
        }
        String normalized = lang.trim().toLowerCase();
        return "zh".equals(normalized) || "cn".equals(normalized) || "zh-cn".equals(normalized);
    }

    private String pickText(boolean preferZh, String zh, String en) {
        if (preferZh && zh != null && !zh.isBlank()) {
            return zh;
        }
        return en;
    }
}
