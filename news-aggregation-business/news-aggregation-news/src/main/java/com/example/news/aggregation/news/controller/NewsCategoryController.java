package com.example.news.aggregation.news.controller;

import com.example.news.aggregation.news.domain.entity.NewsCategory;
import com.example.news.aggregation.news.dto.NewsCategoryDto;
import com.example.news.aggregation.news.infrastructure.mapper.NewsCategoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 新闻分类接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/news/categories")
@RequiredArgsConstructor
public class NewsCategoryController {

    private final NewsCategoryMapper newsCategoryMapper;

    /**
     * 获取分类列表。
     */
    @GetMapping
    public ResponseEntity<List<NewsCategoryDto>> list() {
        List<NewsCategory> categories = newsCategoryMapper.selectAllActive();
        List<NewsCategoryDto> result = categories.stream()
                .map(item -> NewsCategoryDto.builder()
                        .id(item.getId())
                        .name(item.getName())
                        .build())
                .toList();
        log.info("分类列表查询成功，数量={}", result.size());
        return ResponseEntity.ok(result);
    }
}

