package com.example.news.aggregation.news.controller;

import com.example.news.aggregation.news.dto.*;
import com.example.news.aggregation.news.service.RetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/news/retrieval")
@RequiredArgsConstructor
public class RetrievalController {

    private final RetrievalService retrievalService;

    // 关键词检索接口
    @PostMapping("/keyword")
    public ResponseEntity<RetrievalResponse> keyword(@RequestBody RetrievalRequest request) {
        List<RetrievalResultDto> results = retrievalService.keywordSearch(
                request.getQuery(), request.getTopK(), request.getFilters());
        return ResponseEntity.ok(RetrievalResponse.builder().results(results).build());
    }

    // 向量检索接口
    @PostMapping("/vector")
    public ResponseEntity<RetrievalResponse> vector(@RequestBody RetrievalRequest request) {
        List<RetrievalResultDto> results = retrievalService.vectorSearch(
                request.getQuery(), request.getTopK(), request.getMinScore(), request.getFilters());
        return ResponseEntity.ok(RetrievalResponse.builder().results(results).build());
    }

    // 混合检索接口
    @PostMapping("/hybrid")
    public ResponseEntity<RetrievalResponse> hybrid(@RequestBody RetrievalRequest request) {
        List<RetrievalResultDto> results = retrievalService.hybridSearch(
                request.getQuery(), request.getTopK(), request.getMinScore(), request.getFilters());
        return ResponseEntity.ok(RetrievalResponse.builder().results(results).build());
    }

    // RRF 融合接口
    @PostMapping("/rrf")
    public ResponseEntity<RetrievalResponse> rrf(@RequestBody RrfRequest request) {
        List<RetrievalResultDto> results = retrievalService.rrfFusion(
                request.getLists(), request.getTopK());
        return ResponseEntity.ok(RetrievalResponse.builder().results(results).build());
    }

    // 去重接口
    @PostMapping("/dedup")
    public ResponseEntity<RetrievalResponse> dedup(@RequestBody DedupRequest request) {
        List<RetrievalResultDto> results = retrievalService.deduplicate(
                request.getResults(), request.getTopK());
        return ResponseEntity.ok(RetrievalResponse.builder().results(results).build());
    }
}
