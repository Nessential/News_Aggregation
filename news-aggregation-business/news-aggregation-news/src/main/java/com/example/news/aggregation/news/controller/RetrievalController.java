package com.example.news.aggregation.news.controller;

import com.example.news.aggregation.news.dto.DedupRequest;
import com.example.news.aggregation.news.dto.RetrievalRequest;
import com.example.news.aggregation.news.dto.RetrievalResponse;
import com.example.news.aggregation.news.dto.RetrievalResultDto;
import com.example.news.aggregation.news.dto.RrfRequest;
import com.example.news.aggregation.news.service.RetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/news/retrieval")
@RequiredArgsConstructor
public class RetrievalController {

    private final RetrievalService retrievalService;

    @PostMapping("/keyword")
    public ResponseEntity<RetrievalResponse> keyword(@RequestBody RetrievalRequest request) {
        log.info("[DIAG][retrieval-controller] keyword|query={} |topK={} |minScore={} |filters={}",
                querySummary(request.getQuery()), request.getTopK(), request.getMinScore(), summarizeFilters(request.getFilters()));
        List<RetrievalResultDto> results = retrievalService.keywordSearch(
                request.getQuery(), request.getTopK(), request.getFilters());
        return ResponseEntity.ok(RetrievalResponse.builder().results(results).build());
    }

    @PostMapping("/vector")
    public ResponseEntity<RetrievalResponse> vector(@RequestBody RetrievalRequest request) {
        log.info("[DIAG][retrieval-controller] vector|query={} |topK={} |minScore={} |filters={}",
                querySummary(request.getQuery()), request.getTopK(), request.getMinScore(), summarizeFilters(request.getFilters()));
        List<RetrievalResultDto> results = retrievalService.vectorSearch(
                request.getQuery(), request.getTopK(), request.getMinScore(), request.getFilters());
        return ResponseEntity.ok(RetrievalResponse.builder().results(results).build());
    }

    @PostMapping("/hybrid")
    public ResponseEntity<RetrievalResponse> hybrid(@RequestBody RetrievalRequest request) {
        log.info("[DIAG][retrieval-controller] hybrid|query={} |topK={} |minScore={} |filters={}",
                querySummary(request.getQuery()), request.getTopK(), request.getMinScore(), summarizeFilters(request.getFilters()));
        List<RetrievalResultDto> results = retrievalService.hybridSearch(
                request.getQuery(), request.getTopK(), request.getMinScore(), request.getFilters());
        return ResponseEntity.ok(RetrievalResponse.builder().results(results).build());
    }

    @PostMapping("/rrf")
    public ResponseEntity<RetrievalResponse> rrf(@RequestBody RrfRequest request) {
        List<RetrievalResultDto> results = retrievalService.rrfFusion(
                request.getLists(), request.getTopK());
        return ResponseEntity.ok(RetrievalResponse.builder().results(results).build());
    }

    @PostMapping("/dedup")
    public ResponseEntity<RetrievalResponse> dedup(@RequestBody DedupRequest request) {
        List<RetrievalResultDto> results = retrievalService.deduplicate(
                request.getResults(), request.getTopK());
        return ResponseEntity.ok(RetrievalResponse.builder().results(results).build());
    }

    private String summarizeFilters(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return "{}";
        }
        return filters.toString();
    }

    private String querySummary(String query) {
        if (query == null) {
            return "null";
        }
        String compact = Pattern.compile("\\s+").matcher(query).replaceAll(" ").trim();
        boolean hasCjk = compact.codePoints().anyMatch(this::isCjk);
        return "len=" + compact.length() + ",hasCjk=" + hasCjk + ",value=" + truncate(compact, 120);
    }

    private boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
