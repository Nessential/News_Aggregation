package com.example.news.aggregation.llm.springai.controller;

import com.example.news.aggregation.llm.springai.contract.GeneratorDraft;
import com.example.news.aggregation.llm.springai.contract.GeneratorRequest;
import com.example.news.aggregation.llm.springai.contract.Plan;
import com.example.news.aggregation.llm.springai.contract.PlanRequest;
import com.example.news.aggregation.llm.springai.contract.RouterRequest;
import com.example.news.aggregation.llm.springai.contract.RouterResult;
import com.example.news.aggregation.llm.springai.service.GeneratorService;
import com.example.news.aggregation.llm.springai.service.PlannerService;
import com.example.news.aggregation.llm.springai.service.RouterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Graph测试控制器
 * 用于验证Router/Planner/Generator Graph的端到端执行
 */
@Slf4j
@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
public class GraphTestController {

    private final RouterService routerService;
    private final PlannerService plannerService;
    private final GeneratorService generatorService;

    @PostMapping("/router")
    public ResponseEntity<RouterResult> router(@RequestBody RouterRequest request) {
        RouterResult result = routerService.route(request);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/plan")
    public ResponseEntity<Plan> plan(@RequestBody PlanRequest request) {
        Plan plan = plannerService.plan(request);
        return ResponseEntity.ok(plan);
    }

    @PostMapping("/generate")
    public ResponseEntity<GeneratorDraft> generate(@RequestBody GeneratorRequest request) {
        GeneratorDraft draft = generatorService.generate(
                request.getQuery(),
                request.getTaskFamily(),
                request.getEvidence()
        );
        return ResponseEntity.ok(draft);
    }
}
