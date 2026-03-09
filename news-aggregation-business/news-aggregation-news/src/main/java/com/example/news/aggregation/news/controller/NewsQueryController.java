package com.example.news.aggregation.news.controller;

import com.example.news.aggregation.news.domain.entity.News;
import com.example.news.aggregation.news.domain.entity.NewsCategory;
import com.example.news.aggregation.news.dto.ArticlesResponse;
import com.example.news.aggregation.news.dto.IdsRequest;
import com.example.news.aggregation.news.dto.NewsArticleDto;
import com.example.news.aggregation.news.dto.NewsDetailDto;
import com.example.news.aggregation.news.dto.NewsListItemDto;
import com.example.news.aggregation.news.dto.NewsListResponse;
import com.example.news.aggregation.news.infrastructure.mapper.NewsCategoryMapper;
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
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;

/**
 * 新闻查询控制器。
 */
@Slf4j
@RestController
@RequestMapping("/api/news/articles")
@RequiredArgsConstructor
public class NewsQueryController {

    private final NewsMapper newsMapper;
    private final NewsCategoryMapper newsCategoryMapper;
    private final StorageService storageService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    /**
     * 首页新闻列表。
     */
    @GetMapping
    public ResponseEntity<NewsListResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String lang,
            @RequestParam(defaultValue = "false") boolean includeAltLang,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Long categoryId
    ) {
        return listInternal(page, pageSize, lang, includeAltLang, keyword, source, categoryId);
    }

    /**
     * 根据分类查询新闻列表。
     */
    @GetMapping("/by-category/{categoryId}")
    public ResponseEntity<NewsListResponse> listByCategory(
            @PathVariable Long categoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String lang,
            @RequestParam(defaultValue = "false") boolean includeAltLang,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String source
    ) {
        log.info("按分类查询新闻: categoryId={}, page={}, pageSize={}", categoryId, page, pageSize);
        return listInternal(page, pageSize, lang, includeAltLang, keyword, source, categoryId);
    }

    /**
     * 新闻详情。
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
        Set<Long> categoryIds = new HashSet<>();
        if (news.getCategory_id() != null) {
            categoryIds.add(news.getCategory_id());
        }
        Map<Long, String> categoryNameMap = loadCategoryNameMap(categoryIds);
        return ResponseEntity.ok(toDetailDto(news, preferZh, categoryNameMap));
    }

    /**
     * 批量查询文章详情。
     */
    @PostMapping("/by-ids")
    public ResponseEntity<ArticlesResponse> getByIds(@RequestBody IdsRequest request) {
        if (request == null || request.getIds() == null || request.getIds().isEmpty()) {
            log.info("[DIAG][mysql][by-ids] 空请求，直接返回空列表");
            return ResponseEntity.ok(ArticlesResponse.builder().articles(Collections.emptyList()).build());
        }

        log.info("[DIAG][mysql][by-ids] 开始查询，idsCount={}, idsSample={}",
                request.getIds().size(), summarizeIds(request.getIds()));

        List<News> newsList = newsMapper.selectBatchIds(request.getIds());
        Map<Long, String> categoryNameMap = loadCategoryNameMap(
                newsList.stream().map(News::getCategory_id).collect(Collectors.toSet())
        );

        List<NewsArticleDto> articles = newsList.stream()
                .map(news -> toDto(news, categoryNameMap))
                .collect(Collectors.toList());

        log.info("[DIAG][mysql][by-ids] 查询完成，dbCount={}, articleCount={}",
                newsList.size(), articles.size());

        return ResponseEntity.ok(ArticlesResponse.builder().articles(articles).build());
    }

    private ResponseEntity<NewsListResponse> listInternal(
            int page,
            int pageSize,
            String lang,
            boolean includeAltLang,
            String keyword,
            String source,
            Long categoryId
    ) {
        boolean preferZh = isPreferZh(lang);
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(pageSize, 1);
        long offset = (long) (safePage - 1) * safePageSize;

        List<News> records = newsMapper.selectListPage(offset, safePageSize, keyword, source, categoryId);
        Long total = newsMapper.countList(keyword, source, categoryId);
        Map<Long, String> categoryNameMap = loadCategoryNameMap(
                records.stream().map(News::getCategory_id).collect(Collectors.toSet())
        );

        List<NewsListItemDto> items = records.stream()
                .map(news -> toListItemDto(news, preferZh, includeAltLang, categoryNameMap))
                .collect(Collectors.toList());

        return ResponseEntity.ok(NewsListResponse.builder()
                .total(total == null ? 0L : total)
                .page(safePage)
                .pageSize(safePageSize)
                .items(items)
                .build());
    }

    private String summarizeIds(List<Long> ids) {
        return ids.stream().limit(5).map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

    private NewsArticleDto toDto(News news, Map<Long, String> categoryNameMap) {
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
                .categoryId(news.getCategory_id())
                .categoryName(resolveCategoryName(news.getCategory_id(), categoryNameMap))
                .build();
    }

    private NewsListItemDto toListItemDto(
            News news,
            boolean preferZh,
            boolean includeAltLang,
            Map<Long, String> categoryNameMap
    ) {
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
                .categoryId(news.getCategory_id())
                .categoryName(resolveCategoryName(news.getCategory_id(), categoryNameMap))
                .build();
    }

    private NewsDetailDto toDetailDto(News news, boolean preferZh, Map<Long, String> categoryNameMap) {
        if (news == null) {
            return null;
        }
        String title = pickText(preferZh, news.getTitle_cn(), news.getTitle());
        String summary = pickText(preferZh, news.getSummary_cn(), news.getSummary());
        String content = pickText(preferZh, news.getContext_cn(), news.getContext());
        String publishedAt = news.getPublication_time() != null
                ? DATE_FORMATTER.format(Instant.ofEpochMilli(news.getPublication_time()))
                : null;

        return NewsDetailDto.builder()
                .id(news.getId())
                .title(title)
                .summary(summary)
                .content(content)
                .titleCn(news.getTitle_cn())
                .summaryCn(news.getSummary_cn())
                .contentCn(news.getContext_cn())
                .titleEn(news.getTitle())
                .summaryEn(news.getSummary())
                .contentEn(news.getContext())
                .imageUrl(storageService.getAccessUrl(news.getImage_url()))
                .link(news.getLink())
                .source(news.getSource())
                .publishedAt(publishedAt)
                .publicationTime(news.getPublication_time())
                .categoryId(news.getCategory_id())
                .categoryName(resolveCategoryName(news.getCategory_id(), categoryNameMap))
                .build();
    }

    private Map<Long, String> loadCategoryNameMap(Set<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<NewsCategory> categories = newsCategoryMapper.selectBatchIds(categoryIds);
        if (categories == null || categories.isEmpty()) {
            return Collections.emptyMap();
        }
        return categories.stream()
                .collect(Collectors.toMap(NewsCategory::getId, NewsCategory::getName, (a, b) -> a));
    }

    private String resolveCategoryName(Long categoryId, Map<Long, String> categoryNameMap) {
        if (categoryId == null || categoryNameMap == null) {
            return null;
        }
        return categoryNameMap.get(categoryId);
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
