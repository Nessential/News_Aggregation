package com.example.news.aggregation.llm.springai.controller;

import com.example.news.aggregation.llm.springai.tool.RerankTool;
import com.example.news.aggregation.llm.springai.tool.RetrieveTool;
import com.example.news.aggregation.llm.springai.tool.SearchTool;
import com.example.news.aggregation.llm.springai.tool.dto.RetrievalResult;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

/**
 * MCP 工具测试控制器
 * 提供 Search/Retrieve/Rerank 的调试入口
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/mcp/test")
@RequiredArgsConstructor
public class McpToolTestController {

    private final SearchTool searchTool;
    private final RetrieveTool retrieveTool;
    private final RerankTool rerankTool;

    /**
     * 关键词检索测试
     */
    @PostMapping("/search")
    public ResponseEntity<List<RetrievalResult>> search(@RequestBody SearchRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        int topK = request.getTopK() != null ? request.getTopK() : 10;
        return ResponseEntity.ok(searchTool.searchByKeyword(request.getQuery(), topK));
    }

    /**
     * 向量检索测试
     */
    @PostMapping("/retrieve")
    public ResponseEntity<List<RetrievalResult>> retrieve(@RequestBody SearchRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        int topK = request.getTopK() != null ? request.getTopK() : 10;
        return ResponseEntity.ok(retrieveTool.retrieveNews(request.getQuery(), topK));
    }

    /**
     * 混合检索测试
     */
    @PostMapping("/hybrid")
    public ResponseEntity<List<RetrievalResult>> hybrid(@RequestBody SearchRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        int topK = request.getTopK() != null ? request.getTopK() : 10;
        return ResponseEntity.ok(retrieveTool.hybridRetrieve(request.getQuery(), topK));
    }

    /**
     * 重排序测试（自定义 lambda）
     */
    @PostMapping("/rerank")
    public ResponseEntity<List<RetrievalResult>> rerank(@RequestBody RerankRequest request) {
        if (request == null || request.getResults() == null || request.getResults().isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        int topN = request.getTopN() != null ? request.getTopN() : 5;
        double lambda = request.getLambda() != null ? request.getLambda() : 0.7;
        return ResponseEntity.ok(rerankTool.mmrRerank(request.getResults(), topN, lambda));
    }

    /**
     * 重排序测试（默认参数）
     */
    @PostMapping("/simple-rerank")
    public ResponseEntity<List<RetrievalResult>> simpleRerank(@RequestBody RerankSimpleRequest request) {
        if (request == null || request.getResults() == null || request.getResults().isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        int topN = request.getTopN() != null ? request.getTopN() : 5;
        return ResponseEntity.ok(rerankTool.mmrRerank(request.getResults(), topN));
    }

    /**
     * 端到端测试：混合检索 + 重排序
     */
    @PostMapping("/end-to-end")
    public ResponseEntity<List<RetrievalResult>> endToEnd(@RequestBody EndToEndRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        int topK = request.getTopK() != null ? request.getTopK() : 20;
        int topN = request.getTopN() != null ? request.getTopN() : 5;

        List<RetrievalResult> candidates = retrieveTool.hybridRetrieve(request.getQuery(), topK);
        return ResponseEntity.ok(rerankTool.mmrRerank(candidates, topN));
    }

    /**
     * 通用检索请求
     */
    @Data
    public static class SearchRequest {
        /** 查询文本 */
        private String query;
        /** 返回数量 */
        private Integer topK;
    }

    /**
     * 重排序请求（自定义参数）
     */
    @Data
    public static class RerankRequest {
        /** 待重排候选 */
        private List<RetrievalResult> results;
        /** 返回数量 */
        private Integer topN;
        /** 相关性权重 */
        private Double lambda;
    }

    /**
     * 重排序请求（默认权重）
     */
    @Data
    public static class RerankSimpleRequest {
        /** 待重排候选 */
        private List<RetrievalResult> results;
        /** 返回数量 */
        private Integer topN;
    }

    /**
     * 端到端测试请求
     */
    @Data
    public static class EndToEndRequest {
        /** 查询文本 */
        private String query;
        /** 召回数量 */
        private Integer topK;
        /** 返回数量 */
        private Integer topN;
    }
}
