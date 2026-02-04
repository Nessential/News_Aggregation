package com.example.news.aggregation.llm.springai.controller;

import com.example.news.aggregation.llm.springai.contract.RouteRequest;
import com.example.news.aggregation.llm.springai.contract.RouterResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Router 控制器
 * 当前为简化规则实现，后续可替换为真正的 LLM Router
 */
@Slf4j
@RestController
@RequestMapping("/api/router")
@RequiredArgsConstructor
public class RouterController {

    // 路由决策接口
    @PostMapping("/route")
    public ResponseEntity<RouterResult> route(@RequestBody RouteRequest request) {
        // TODO: 后续改为 LLM Router (Function Calling/JSON Schema) 决策
        String query = request != null && request.getQuery() != null
                ? request.getQuery().toLowerCase(Locale.ROOT)
                : "";
        String taskFamily;
        if (query.contains("总结") || query.contains("汇总") || query.contains("summary")) {
            taskFamily = "SUMMARY";
        } else if (query.contains("搜索") || query.contains("查找") || query.contains("search")) {
            taskFamily = "SEARCH";
        } else {
            taskFamily = "QA";
        }

        Map<String, Object> params = new HashMap<>();
        if (request != null && request.getConstraints() != null) {
            params.putAll(request.getConstraints());
        }

        RouterResult result = RouterResult.builder()
                .taskFamily(taskFamily)
                .retrievalMode("HYBRID")
                .riskLevel("LOW")
                .needsClarification(false)
                .params(params)
                .build();

        return ResponseEntity.ok(result);
    }
}
