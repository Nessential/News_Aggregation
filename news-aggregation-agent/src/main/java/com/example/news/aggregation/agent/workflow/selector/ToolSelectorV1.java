package com.example.news.aggregation.agent.workflow.selector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于稳定规则的工具选择器：
 * 以全局熔断状态为硬约束，本地健康窗口仅作为退化信号。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolSelectorV1 {

    private final ToolSelectionPolicy selectionPolicy;
    private final ToolCircuitBreaker circuitBreaker;
    private final ToolHealthWindow healthWindow;
    private final ToolSelectorProperties properties;

    public ToolSelectionResult select(String capability,
                                      String primaryTool,
                                      List<String> fallbackTools,
                                      String selectedTool,
                                      boolean forceReselect,
                                      boolean fallbackAction) {
        List<String> disabledCandidates = selectionPolicy.buildCandidates(capability, primaryTool, fallbackTools);
        boolean selectedReusable = selectedTool != null
                && !selectedTool.isBlank()
                && disabledCandidates.contains(selectedTool)
                && !forceReselect
                && !fallbackAction;
        if (!properties.isEnabled()) {
            if (disabledCandidates.isEmpty()) {
                log.warn("[tool-selector] 选择器关闭且无候选工具，返回 fallback_unavailable|capability={}", capability);
                return ToolSelectionResult.builder()
                        .selectedTool(null)
                        .reasonCode("fallback_unavailable")
                        .candidates(disabledCandidates)
                        .circuitStateByTool(Map.of())
                        .healthSnapshotByTool(Map.of())
                        .build();
            }
            if (selectedReusable) {
                log.info("[tool-selector] 选择器关闭，复用已选工具|capability={} |selectedTool={}", capability, selectedTool);
                return ToolSelectionResult.builder()
                        .selectedTool(selectedTool)
                        .reasonCode("reuse_selected_tool")
                        .candidates(disabledCandidates)
                        .circuitStateByTool(Map.of())
                        .healthSnapshotByTool(Map.of())
                        .build();
            }
            log.info("[tool-selector] 选择器关闭，按固定顺序选择首个候选|capability={} |selectedTool={}",
                    capability, disabledCandidates.getFirst());
            return ToolSelectionResult.builder()
                    .selectedTool(disabledCandidates.getFirst())
                    .reasonCode("selector_disabled")
                    .candidates(disabledCandidates)
                    .circuitStateByTool(Map.of())
                    .healthSnapshotByTool(Map.of())
                    .build();
        }
        List<String> candidates = disabledCandidates;
        Map<String, String> circuitStateByTool = new LinkedHashMap<>();
        Map<String, ToolHealthSnapshot> healthSnapshotByTool = new LinkedHashMap<>();
        boolean primaryHealthDegraded = false;
        boolean casExhaustedObserved = false;
        if (candidates.isEmpty()) {
            log.warn("[tool-selector] 候选工具为空，无法选择|capability={}", capability);
            return noSelection("fallback_unavailable", candidates, circuitStateByTool, healthSnapshotByTool);
        }

        if (selectedReusable) {
            ToolCircuitDecision decision = circuitBreaker.allowRequest(selectedTool, capability);
            circuitStateByTool.put(selectedTool, decision.getState().name());
            healthSnapshotByTool.put(selectedTool, healthWindow.snapshot(selectedTool, capability));
            if (decision.isAllowed()) {
                log.info("[tool-selector] 复用已选工具成功|capability={} |selectedTool={} |circuitState={}",
                        capability, selectedTool, decision.getState());
                return ToolSelectionResult.builder()
                        .selectedTool(selectedTool)
                        .reasonCode("reuse_selected_tool")
                        .candidates(candidates)
                        .circuitStateByTool(circuitStateByTool)
                        .healthSnapshotByTool(healthSnapshotByTool)
                        .build();
            }
        }

        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            ToolCircuitDecision decision = circuitBreaker.allowRequest(candidate, capability);
            circuitStateByTool.put(candidate, decision.getState().name());
            ToolHealthSnapshot snapshot = healthWindow.snapshot(candidate, capability);
            healthSnapshotByTool.put(candidate, snapshot);
            if (!decision.isAllowed()) {
                if (decision.getReasonCode() != null && decision.getReasonCode().contains("cas_exhausted")) {
                    casExhaustedObserved = true;
                }
                continue;
            }
            if (isPrimary(primaryTool, candidate)
                    && !fallbackAction
                    && !forceReselect
                    && decision.getState() == ToolCircuitState.CLOSED
                    && isPrimaryHealthDegraded(snapshot)) {
                primaryHealthDegraded = true;
                log.info("[tool-selector] 主工具健康退化，尝试回退候选|capability={} |primaryTool={}", capability, candidate);
                continue;
            }
            String reasonCode = buildReasonCode(primaryTool, candidate, fallbackAction, decision, primaryHealthDegraded);
            log.info("[tool-selector] 工具选择完成|capability={} |selectedTool={} |reasonCode={} |circuitState={}",
                    capability, candidate, reasonCode, decision.getState());
            return ToolSelectionResult.builder()
                    .selectedTool(candidate)
                    .reasonCode(reasonCode)
                    .candidates(candidates)
                    .circuitStateByTool(circuitStateByTool)
                    .healthSnapshotByTool(healthSnapshotByTool)
                    .build();
        }

        log.warn("[tool-selector] 所有候选均不可用|capability={} |reasonCode={}",
                capability, casExhaustedObserved ? "fallback_cas_exhausted" : "fallback_unavailable");
        return noSelection(casExhaustedObserved ? "fallback_cas_exhausted" : "fallback_unavailable",
                candidates,
                circuitStateByTool,
                healthSnapshotByTool);
    }

    public void recordSuccess(String selectedTool, String capability, long latencyMs) {
        if (!properties.isEnabled() || selectedTool == null || selectedTool.isBlank()) {
            return;
        }
        healthWindow.recordSuccess(selectedTool, capability, latencyMs);
        ToolCircuitTransitionOutcome outcome = circuitBreaker.onSuccess(selectedTool, capability, "tool_exec_success");
        if (outcome == ToolCircuitTransitionOutcome.CAS_EXHAUSTED) {
            log.warn("[tool-selector] 成功回收熔断状态时 CAS 重试耗尽|tool={} |capability={}", selectedTool, capability);
        }
    }

    public void recordFailure(String selectedTool,
                              String capability,
                              ToolFailureCategory failureCategory,
                              long latencyMs,
                              String reasonCode) {
        if (!properties.isEnabled() || selectedTool == null || selectedTool.isBlank()) {
            return;
        }
        if (failureCategory == null) {
            failureCategory = ToolFailureCategory.OTHER;
        }
        healthWindow.recordFailure(selectedTool, capability, failureCategory, latencyMs);
        if (failureCategory == ToolFailureCategory.INFRA_FAIL || failureCategory == ToolFailureCategory.TIMEOUT) {
            ToolHealthSnapshot snapshot = healthWindow.snapshot(selectedTool, capability);
            boolean thresholdOpen = snapshot.getSampleCount() >= Math.max(1, properties.getHealthMinSamples())
                    && (((snapshot.getInfraFailCount() + snapshot.getTimeoutCount())
                    >= Math.max(1, properties.getPrimaryInfraFailCountThreshold()))
                    || (snapshot.getInfraFailRate() + snapshot.getTimeoutRate())
                    >= Math.max(0.0, properties.getPrimaryInfraFailRateThreshold()));
            ToolCircuitState currentState = circuitBreaker.currentState(selectedTool, capability);
            if (currentState == ToolCircuitState.HALF_OPEN) {
                ToolCircuitTransitionOutcome outcome = circuitBreaker.onFailure(
                        selectedTool,
                        capability,
                        failureCategory,
                        nonBlank(reasonCode, "probe_fail_open")
                );
                if (outcome == ToolCircuitTransitionOutcome.CAS_EXHAUSTED) {
                    log.warn("[tool-selector] HALF_OPEN 失败回 OPEN 时 CAS 重试耗尽|tool={} |capability={}", selectedTool, capability);
                }
                return;
            }
            if (thresholdOpen) {
                ToolCircuitTransitionOutcome outcome = circuitBreaker.forceOpen(
                        selectedTool,
                        capability,
                        nonBlank(reasonCode, "infra_fail_threshold")
                );
                if (outcome == ToolCircuitTransitionOutcome.CAS_EXHAUSTED) {
                    log.warn("[tool-selector] 达到阈值强制 OPEN 时 CAS 重试耗尽|tool={} |capability={}", selectedTool, capability);
                }
            }
        }
    }

    private ToolSelectionResult noSelection(String reasonCode,
                                            List<String> candidates,
                                            Map<String, String> circuitStateByTool,
                                            Map<String, ToolHealthSnapshot> healthSnapshotByTool) {
        return ToolSelectionResult.builder()
                .selectedTool(null)
                .reasonCode(reasonCode)
                .candidates(candidates)
                .circuitStateByTool(circuitStateByTool)
                .healthSnapshotByTool(healthSnapshotByTool)
                .build();
    }

    private boolean isPrimary(String primaryTool, String candidate) {
        return primaryTool != null && primaryTool.equals(candidate);
    }

    private boolean isPrimaryHealthDegraded(ToolHealthSnapshot snapshot) {
        if (snapshot == null || snapshot.getSampleCount() < Math.max(1, properties.getHealthMinSamples())) {
            return false;
        }
        double threshold = Math.max(0.0, properties.getPrimaryInfraFailRateThreshold());
        int failCountThreshold = Math.max(1, properties.getPrimaryInfraFailCountThreshold());
        long infraFailures = snapshot.getInfraFailCount() + snapshot.getTimeoutCount();
        double infraRate = snapshot.getInfraFailRate() + snapshot.getTimeoutRate();
        return infraRate >= threshold || infraFailures >= failCountThreshold;
    }

    private String buildReasonCode(String primaryTool,
                                   String candidate,
                                   boolean fallbackAction,
                                   ToolCircuitDecision decision,
                                   boolean primaryHealthDegraded) {
        if (isPrimary(primaryTool, candidate)) {
            if (decision.getState() == ToolCircuitState.HALF_OPEN) {
                return "primary_half_open_probe";
            }
            return "primary_healthy";
        }
        if (fallbackAction) {
            return "fallback_tool";
        }
        if (primaryHealthDegraded) {
            return "fallback_primary_health_degraded";
        }
        if (decision.getState() == ToolCircuitState.HALF_OPEN) {
            return "fallback_half_open_probe";
        }
        return "fallback_selected";
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
