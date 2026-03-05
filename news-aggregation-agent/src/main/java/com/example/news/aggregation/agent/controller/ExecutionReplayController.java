package com.example.news.aggregation.agent.controller;

import com.example.news.aggregation.agent.dto.ExecutionReplayResponse;
import com.example.news.aggregation.agent.execution.service.ExecutionReplayQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Week6 回放 API：单 run 聚合回放。
 */
@Slf4j
@RestController
@RequestMapping("/api/execution")
@RequiredArgsConstructor
public class ExecutionReplayController {

    private final ExecutionReplayQueryService replayQueryService;

    @GetMapping("/replay/{runId}")
    public ResponseEntity<?> replay(@PathVariable String runId,
                                    @RequestHeader(name = "X-Tenant-Id", required = false) String headerTenantId,
                                    @RequestParam(name = "tenantId", required = false) String queryTenantId,
                                    @RequestHeader(name = "X-Session-Id", required = false) String headerSessionId,
                                    @RequestParam(name = "sessionId", required = false) String querySessionId,
                                    @RequestParam(name = "includeRawPayload", required = false, defaultValue = "false")
                                    boolean includeRawPayload) {
        String requesterTenantId = firstNonBlank(headerTenantId, queryTenantId);
        String requesterSessionId = firstNonBlank(headerSessionId, querySessionId);
        try {
            ExecutionReplayResponse response = replayQueryService.buildReplay(
                    runId,
                    requesterTenantId,
                    requesterSessionId,
                    includeRawPayload
            );
            if (response == null) {
                log.warn("[execution-replay-api] run 不存在|runId={} |reasonCode=run_not_found", runId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(java.util.Map.of(
                        "success", false,
                        "reasonCode", "run_not_found",
                        "runId", runId
                ));
            }
            return ResponseEntity.ok(response);
        } catch (IllegalStateException featureDisabled) {
            if (!"feature_disabled_replay".equals(featureDisabled.getMessage())) {
                throw featureDisabled;
            }
            log.warn("[execution-replay-api] 回放接口关闭|runId={} |reasonCode={}", runId, featureDisabled.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(java.util.Map.of(
                    "success", false,
                    "reasonCode", "feature_disabled_replay",
                    "runId", runId
            ));
        } catch (SecurityException unauthorized) {
            log.warn("[execution-replay-api] 回放鉴权失败|runId={} |reasonCode={}", runId, unauthorized.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(java.util.Map.of(
                    "success", false,
                    "reasonCode", unauthorized.getMessage(),
                    "runId", runId
            ));
        } catch (IllegalArgumentException badRequest) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(java.util.Map.of(
                    "success", false,
                    "reasonCode", badRequest.getMessage(),
                    "runId", runId
            ));
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }
}
