package com.example.news.aggregation.agent.execution.service;

import com.example.news.aggregation.agent.execution.config.ReplanControlProperties;
import com.example.news.aggregation.agent.execution.domain.ExecutionRunEntity;
import com.example.news.aggregation.agent.execution.domain.ExecutionStepRunEntity;
import com.example.news.aggregation.agent.execution.model.ReplanChangeProof;
import com.example.news.aggregation.agent.execution.model.ReplanEvidenceSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Week5 重规划门控服务：预算、证据、变化证明。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplanGuardService {

    private final ReplanControlProperties properties;
    private final ExecutionRunService executionRunService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record BudgetCheckResult(boolean allowed, String reasonCode) {
    }

    public record EvidenceCheckResult(boolean enabled, boolean sufficient, String reasonCode) {
    }

    /**
     * 预算门控：超限时返回明确 reasonCode。
     */
    public BudgetCheckResult checkBudget(ExecutionRunEntity run, ExecutionStepRunEntity step) {
        int runCount = run == null || run.getReplanCountRun() == null ? 0 : Math.max(0, run.getReplanCountRun());
        int stepCount = step == null || step.getReplanCountStep() == null ? 0 : Math.max(0, step.getReplanCountStep());
        if (runCount >= Math.max(0, properties.getMaxReplansPerRun())) {
            return new BudgetCheckResult(false, "replan_budget_exhausted_run");
        }
        if (stepCount >= Math.max(0, properties.getMaxReplansPerStep())) {
            return new BudgetCheckResult(false, "replan_budget_exhausted_step");
        }
        return new BudgetCheckResult(true, null);
    }

    /**
     * 证据门控：capability 级优先，缺省回落全局阈值。
     */
    public EvidenceCheckResult checkEvidence(String capability, ReplanEvidenceSnapshot snapshot) {
        ReplanRule rule = resolveEvidenceRule(capability);
        boolean enabled = rule.enabled();
        if (!enabled) {
            return new EvidenceCheckResult(false, true, null);
        }
        int sourceCount = snapshot == null || snapshot.getSourceCount() == null ? 0 : Math.max(0, snapshot.getSourceCount());
        double coverageRate = snapshot == null || snapshot.getCoverageRate() == null ? 0.0d : Math.max(0.0d, snapshot.getCoverageRate());
        int clusterCount = snapshot == null || snapshot.getClusterCount() == null ? 0 : Math.max(0, snapshot.getClusterCount());
        if (sourceCount < rule.minSourceCount() || coverageRate < rule.minCoverageRate() || clusterCount < rule.minClusterCount()) {
            log.info("[replan-guard] 证据门控未通过|capability={} |sourceCount={} |coverageRate={} |clusterCount={} |threshold={}/{}/{}",
                    capability, sourceCount, coverageRate, clusterCount, rule.minSourceCount(), rule.minCoverageRate(), rule.minClusterCount());
            return new EvidenceCheckResult(true, false, "evidence_insufficient");
        }
        return new EvidenceCheckResult(true, true, null);
    }

    /**
     * 变化证明：使用规范化签名避免序列化顺序导致误判。
     */
    public ReplanChangeProof buildChangeProof(String toolName,
                                              Map<String, Object> previousInput,
                                              Map<String, Object> candidateInput,
                                              String constraintsDigest,
                                              String depsDigest) {
        String previous = normalizedSignature(toolName, previousInput, constraintsDigest, depsDigest);
        String candidate = normalizedSignature(toolName, candidateInput, constraintsDigest, depsDigest);
        boolean effective = !previous.equals(candidate);
        if (!effective) {
            return ReplanChangeProof.builder()
                    .effectiveChange(false)
                    .previousSignature(previous)
                    .candidateSignature(candidate)
                    .reasonCode("replan_no_effective_change")
                    .build();
        }
        return ReplanChangeProof.builder()
                .effectiveChange(true)
                .previousSignature(previous)
                .candidateSignature(candidate)
                .reasonCode("replan_effective_change")
                .build();
    }

    public boolean isNonRetryableReason(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank() || properties.getNonRetryableReasonCodes() == null) {
            return false;
        }
        String normalized = reasonCode.toLowerCase(Locale.ROOT);
        for (String blocked : properties.getNonRetryableReasonCodes()) {
            if (blocked == null || blocked.isBlank()) {
                continue;
            }
            if (normalized.equals(blocked.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 预算递增时机：仅在同事务成功切换 active_plan_version 后才允许扣减。
     */
    public boolean increaseBudgetAfterPlanActivated(String runId, int newActivePlanVersion) {
        boolean updated = executionRunService.switchActivePlanVersionAndIncreaseReplanCount(runId, newActivePlanVersion);
        if (!updated) {
            log.warn("[replan-guard] 预算未扣减：active_plan_version 切换失败|runId={} |newActivePlanVersion={}",
                    runId, newActivePlanVersion);
        }
        return updated;
    }

    public String normalizedSignature(String toolName,
                                      Map<String, Object> input,
                                      String constraintsDigest,
                                      String depsDigest) {
        String canonicalInput = canonicalJson(input == null ? Map.of() : input);
        String normalizedConstraints = constraintsDigest == null ? "" : constraintsDigest.trim();
        String normalizedDeps = depsDigest == null ? "" : depsDigest.trim();
        String raw = (toolName == null ? "" : toolName.trim()) + "|" + canonicalInput + "|" + normalizedConstraints + "|" + normalizedDeps;
        return executionRunService.sha256Hex(raw);
    }

    private String canonicalJson(Object value) {
        try {
            return objectMapper.writeValueAsString(canonicalize(value));
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    @SuppressWarnings("unchecked")
    private Object canonicalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                sorted.put(String.valueOf(entry.getKey()), canonicalize(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object item : list) {
                normalized.add(canonicalize(item));
            }
            return normalized;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return value;
        }
        try {
            Map<String, Object> mapped = objectMapper.convertValue(value, LinkedHashMap.class);
            return canonicalize(mapped);
        } catch (IllegalArgumentException e) {
            return String.valueOf(value);
        }
    }

    private ReplanRule resolveEvidenceRule(String capability) {
        int defaultMinSource = Math.max(0, properties.getMinSourceCount());
        double defaultMinCoverage = Math.max(0.0d, properties.getMinCoverageRate());
        int defaultMinCluster = Math.max(0, properties.getMinClusterCount());
        boolean enabled = true;
        if (capability == null || capability.isBlank() || properties.getEvidence() == null) {
            return new ReplanRule(enabled, defaultMinSource, defaultMinCoverage, defaultMinCluster);
        }
        ReplanControlProperties.EvidenceRule capabilityRule = properties.getEvidence().get(capability);
        if (capabilityRule == null) {
            return new ReplanRule(enabled, defaultMinSource, defaultMinCoverage, defaultMinCluster);
        }
        boolean resolvedEnabled = capabilityRule.getEnabled() == null || capabilityRule.getEnabled();
        int resolvedSource = capabilityRule.getMinSourceCount() == null ? defaultMinSource : Math.max(0, capabilityRule.getMinSourceCount());
        double resolvedCoverage = capabilityRule.getMinCoverageRate() == null ? defaultMinCoverage : Math.max(0.0d, capabilityRule.getMinCoverageRate());
        int resolvedCluster = capabilityRule.getMinClusterCount() == null ? defaultMinCluster : Math.max(0, capabilityRule.getMinClusterCount());
        return new ReplanRule(resolvedEnabled, resolvedSource, resolvedCoverage, resolvedCluster);
    }

    private record ReplanRule(boolean enabled, int minSourceCount, double minCoverageRate, int minClusterCount) {
    }
}
