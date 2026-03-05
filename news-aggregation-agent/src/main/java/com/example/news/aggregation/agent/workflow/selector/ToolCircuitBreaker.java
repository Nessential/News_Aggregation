package com.example.news.aggregation.agent.workflow.selector;

import com.example.news.aggregation.agent.execution.config.ExecutionPersistenceProperties;
import com.example.news.aggregation.agent.execution.domain.ToolCircuitStateEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * tool + capability 维度的全局熔断器，HALF_OPEN 探测由单 owner 执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolCircuitBreaker {

    private final ToolCircuitStateStore stateStore;
    private final ToolSelectorProperties properties;
    private final ExecutionPersistenceProperties executionPersistenceProperties;

    public ToolCircuitDecision allowRequest(String toolName, String capability) {
        ToolCircuitStateEntity current = stateStore.getOrCreate(toolName, capability);
        if (current == null) {
            return ToolCircuitDecision.builder()
                    .allowed(true)
                    .halfOpenProbe(false)
                    .state(ToolCircuitState.CLOSED)
                    .reasonCode("circuit_state_missing")
                    .build();
        }
        ToolCircuitState state = ToolCircuitState.from(current.getState());
        Date now = new Date();
        String workerId = workerId();

        if (state == ToolCircuitState.CLOSED) {
            return decision(true, false, ToolCircuitState.CLOSED, "circuit_closed");
        }

        if (state == ToolCircuitState.OPEN) {
            Date openUntil = current.getOpenUntil();
            if (openUntil != null && openUntil.after(now)) {
                return decision(false, false, ToolCircuitState.OPEN, "tool_open_circuit");
            }
            // OPEN 冷却结束后尝试抢占 HALF_OPEN owner，仅 owner 可放行单探测请求。
            ToolCircuitTransitionOutcome transition = openToHalfOpenOwner(toolName, capability, workerId);
            if (transition == ToolCircuitTransitionOutcome.APPLIED) {
                return decision(true, true, ToolCircuitState.HALF_OPEN, "half_open_probe_owner");
            }
            ToolCircuitStateEntity refreshed = stateStore.getOrCreate(toolName, capability);
            if (transition == ToolCircuitTransitionOutcome.CAS_EXHAUSTED
                    && refreshed != null
                    && ToolCircuitState.from(refreshed.getState()) == ToolCircuitState.OPEN
                    && (refreshed.getOpenUntil() == null || !refreshed.getOpenUntil().after(new Date()))) {
                return decision(false, false, ToolCircuitState.OPEN, "half_open_transition_cas_exhausted");
            }
            return resolveHalfOpenOrOpen(refreshed, toolName, capability, workerId);
        }

        return resolveHalfOpenOrOpen(current, toolName, capability, workerId);
    }

    public ToolCircuitState currentState(String toolName, String capability) {
        ToolCircuitStateEntity entity = stateStore.getOrCreate(toolName, capability);
        if (entity == null) {
            return ToolCircuitState.CLOSED;
        }
        return ToolCircuitState.from(entity.getState());
    }

    public ToolCircuitTransitionOutcome onSuccess(String toolName, String capability, String reasonCode) {
        String workerId = workerId();
        for (int i = 0; i < maxCasRetries(); i++) {
            ToolCircuitStateEntity current = stateStore.getOrCreate(toolName, capability);
            if (current == null) {
                return ToolCircuitTransitionOutcome.SKIPPED;
            }
            ToolCircuitState state = ToolCircuitState.from(current.getState());
            if (state != ToolCircuitState.HALF_OPEN) {
                return ToolCircuitTransitionOutcome.SKIPPED;
            }
            if (!workerId.equals(current.getHalfOpenOwner())) {
                return ToolCircuitTransitionOutcome.SKIPPED;
            }
            int rows = stateStore.updateStateWithCas(
                    toolName,
                    capability,
                    defaultVersion(current.getLockVersion()),
                    ToolCircuitState.CLOSED,
                    null,
                    null,
                    null,
                    nonBlank(reasonCode, "probe_success_closed")
            );
            if (rows > 0) {
                return ToolCircuitTransitionOutcome.APPLIED;
            }
        }
        log.warn("[tool-circuit] HALF_OPEN 成功回收为 CLOSED 时 CAS 重试耗尽|tool={} |capability={}", toolName, capability);
        return ToolCircuitTransitionOutcome.CAS_EXHAUSTED;
    }

    public ToolCircuitTransitionOutcome onFailure(String toolName,
                                                  String capability,
                                                  ToolFailureCategory category,
                                                  String reasonCode) {
        if (category != ToolFailureCategory.INFRA_FAIL && category != ToolFailureCategory.TIMEOUT) {
            return ToolCircuitTransitionOutcome.SKIPPED;
        }
        String workerId = workerId();
        for (int i = 0; i < maxCasRetries(); i++) {
            ToolCircuitStateEntity current = stateStore.getOrCreate(toolName, capability);
            if (current == null) {
                return ToolCircuitTransitionOutcome.SKIPPED;
            }
            ToolCircuitState state = ToolCircuitState.from(current.getState());
            if (state == ToolCircuitState.HALF_OPEN) {
                if (!workerId.equals(current.getHalfOpenOwner())) {
                    return ToolCircuitTransitionOutcome.SKIPPED;
                }
                int rows = stateStore.updateStateWithCas(
                        toolName,
                        capability,
                        defaultVersion(current.getLockVersion()),
                        ToolCircuitState.OPEN,
                        nextOpenUntil(),
                        null,
                        null,
                        nonBlank(reasonCode, "probe_failure_open")
                );
                if (rows > 0) {
                    return ToolCircuitTransitionOutcome.APPLIED;
                }
                continue;
            }
            if (state == ToolCircuitState.CLOSED) {
                int rows = stateStore.updateStateWithCas(
                        toolName,
                        capability,
                        defaultVersion(current.getLockVersion()),
                        ToolCircuitState.OPEN,
                        nextOpenUntil(),
                        null,
                        null,
                        nonBlank(reasonCode, "infra_failure_open")
                );
                if (rows > 0) {
                    return ToolCircuitTransitionOutcome.APPLIED;
                }
                continue;
            }
            return ToolCircuitTransitionOutcome.SKIPPED;
        }
        log.warn("[tool-circuit] 故障触发 OPEN 时 CAS 重试耗尽|tool={} |capability={}", toolName, capability);
        return ToolCircuitTransitionOutcome.CAS_EXHAUSTED;
    }

    public ToolCircuitTransitionOutcome forceOpen(String toolName, String capability, String reasonCode) {
        for (int i = 0; i < maxCasRetries(); i++) {
            ToolCircuitStateEntity current = stateStore.getOrCreate(toolName, capability);
            if (current == null) {
                return ToolCircuitTransitionOutcome.SKIPPED;
            }
            if (ToolCircuitState.from(current.getState()) == ToolCircuitState.OPEN
                    && current.getOpenUntil() != null
                    && current.getOpenUntil().after(new Date())) {
                return ToolCircuitTransitionOutcome.SKIPPED;
            }
            int rows = stateStore.updateStateWithCas(
                    toolName,
                    capability,
                    defaultVersion(current.getLockVersion()),
                    ToolCircuitState.OPEN,
                    nextOpenUntil(),
                    null,
                    null,
                    nonBlank(reasonCode, "force_open")
            );
            if (rows > 0) {
                return ToolCircuitTransitionOutcome.APPLIED;
            }
        }
        log.warn("[tool-circuit] 强制 OPEN 时 CAS 重试耗尽|tool={} |capability={}", toolName, capability);
        return ToolCircuitTransitionOutcome.CAS_EXHAUSTED;
    }

    private ToolCircuitTransitionOutcome openToHalfOpenOwner(String toolName, String capability, String workerId) {
        for (int i = 0; i < maxCasRetries(); i++) {
            ToolCircuitStateEntity current = stateStore.getOrCreate(toolName, capability);
            if (current == null) {
                return ToolCircuitTransitionOutcome.SKIPPED;
            }
            if (ToolCircuitState.from(current.getState()) != ToolCircuitState.OPEN) {
                return ToolCircuitTransitionOutcome.SKIPPED;
            }
            Date now = new Date();
            if (current.getOpenUntil() != null && current.getOpenUntil().after(now)) {
                return ToolCircuitTransitionOutcome.SKIPPED;
            }
            int rows = stateStore.updateStateWithCas(
                    toolName,
                    capability,
                    defaultVersion(current.getLockVersion()),
                    ToolCircuitState.HALF_OPEN,
                    null,
                    workerId,
                    nextOwnerLeaseUntil(),
                    "half_open_probe_owner"
            );
            if (rows > 0) {
                return ToolCircuitTransitionOutcome.APPLIED;
            }
        }
        log.warn("[tool-circuit] OPEN 转 HALF_OPEN 抢占 owner 时 CAS 重试耗尽|tool={} |capability={}", toolName, capability);
        return ToolCircuitTransitionOutcome.CAS_EXHAUSTED;
    }

    private ToolCircuitDecision resolveHalfOpenOrOpen(ToolCircuitStateEntity entity,
                                                      String toolName,
                                                      String capability,
                                                      String workerId) {
        if (entity == null) {
            return decision(true, false, ToolCircuitState.CLOSED, "circuit_state_missing");
        }
        ToolCircuitState state = ToolCircuitState.from(entity.getState());
        Date now = new Date();
        if (state == ToolCircuitState.HALF_OPEN) {
            if (workerId.equals(entity.getHalfOpenOwner())
                    && entity.getOwnerLeaseUntil() != null
                    && entity.getOwnerLeaseUntil().after(now)) {
                return decision(true, true, ToolCircuitState.HALF_OPEN, "half_open_probe_owner");
            }
            if (entity.getOwnerLeaseUntil() == null || !entity.getOwnerLeaseUntil().after(now)) {
                ToolCircuitTransitionOutcome takeover = takeoverHalfOpenOwner(toolName, capability, workerId);
                if (takeover == ToolCircuitTransitionOutcome.APPLIED) {
                    return decision(true, true, ToolCircuitState.HALF_OPEN, "half_open_probe_owner_takeover");
                }
                if (takeover == ToolCircuitTransitionOutcome.CAS_EXHAUSTED) {
                    return decision(false, false, ToolCircuitState.HALF_OPEN, "half_open_takeover_cas_exhausted");
                }
            }
            return decision(false, false, ToolCircuitState.HALF_OPEN, "half_open_not_owner");
        }
        if (state == ToolCircuitState.OPEN) {
            Date openUntil = entity.getOpenUntil();
            if (openUntil != null && openUntil.after(now)) {
                return decision(false, false, ToolCircuitState.OPEN, "tool_open_circuit");
            }
        }
        return decision(state == ToolCircuitState.CLOSED, false, state, "circuit_state_" + state.name().toLowerCase());
    }

    private ToolCircuitTransitionOutcome takeoverHalfOpenOwner(String toolName, String capability, String workerId) {
        for (int i = 0; i < maxCasRetries(); i++) {
            ToolCircuitStateEntity current = stateStore.getOrCreate(toolName, capability);
            if (current == null) {
                return ToolCircuitTransitionOutcome.SKIPPED;
            }
            if (ToolCircuitState.from(current.getState()) != ToolCircuitState.HALF_OPEN) {
                return ToolCircuitTransitionOutcome.SKIPPED;
            }
            Date now = new Date();
            if (current.getOwnerLeaseUntil() != null && current.getOwnerLeaseUntil().after(now)) {
                return ToolCircuitTransitionOutcome.SKIPPED;
            }
            int rows = stateStore.updateStateWithCas(
                    toolName,
                    capability,
                    defaultVersion(current.getLockVersion()),
                    ToolCircuitState.HALF_OPEN,
                    null,
                    workerId,
                    nextOwnerLeaseUntil(),
                    "half_open_probe_owner_takeover"
            );
            if (rows > 0) {
                return ToolCircuitTransitionOutcome.APPLIED;
            }
        }
        log.warn("[tool-circuit] HALF_OPEN owner 接管时 CAS 重试耗尽|tool={} |capability={}", toolName, capability);
        return ToolCircuitTransitionOutcome.CAS_EXHAUSTED;
    }

    private ToolCircuitDecision decision(boolean allowed,
                                         boolean halfOpenProbe,
                                         ToolCircuitState state,
                                         String reasonCode) {
        return ToolCircuitDecision.builder()
                .allowed(allowed)
                .halfOpenProbe(halfOpenProbe)
                .state(state)
                .reasonCode(reasonCode)
                .build();
    }

    private Date nextOpenUntil() {
        long seconds = Math.max(1L, properties.getCircuitOpenSeconds());
        return new Date(System.currentTimeMillis() + seconds * 1000L);
    }

    private Date nextOwnerLeaseUntil() {
        long seconds = Math.max(1L, properties.getHalfOpenOwnerLeaseSeconds());
        return new Date(System.currentTimeMillis() + seconds * 1000L);
    }

    private int maxCasRetries() {
        return Math.max(1, properties.getCasMaxRetries());
    }

    private Integer defaultVersion(Integer lockVersion) {
        return lockVersion == null ? 0 : lockVersion;
    }

    private String workerId() {
        String workerId = executionPersistenceProperties.getPersistence().getWorkerId();
        return workerId == null || workerId.isBlank() ? "local-worker" : workerId;
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
