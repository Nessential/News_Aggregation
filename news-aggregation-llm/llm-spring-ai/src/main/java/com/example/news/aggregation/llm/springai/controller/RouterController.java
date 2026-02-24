package com.example.news.aggregation.llm.springai.controller;

import com.example.news.aggregation.llm.springai.contract.RouterResult;
import com.example.news.aggregation.llm.springai.contract.RouterRequest;
import com.example.news.aggregation.llm.springai.service.RouterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Router 控制器
 * 基于RouterGraph输出结构化路由结果
 */
@Slf4j
@RestController
@RequestMapping("/api/router")
@RequiredArgsConstructor
public class RouterController {

    private final RouterService routerService;

    // 路由决策接口
    @PostMapping("/route")
    public ResponseEntity<RouterResult> route(@RequestBody RouterRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(RouterResult.defaultQA());
        }
        RouterResult result = routerService.route(request);
        return ResponseEntity.ok(result);
    }
}
