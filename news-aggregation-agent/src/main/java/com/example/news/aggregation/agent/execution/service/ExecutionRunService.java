package com.example.news.aggregation.agent.execution.service;

import com.example.news.aggregation.agent.execution.config.ExecutionPersistenceProperties;
import com.example.news.aggregation.agent.execution.domain.ExecutionEventLogEntity;
import com.example.news.aggregation.agent.execution.domain.ExecutionRunEntity;
import com.example.news.aggregation.agent.execution.domain.ExecutionStepRunEntity;
import com.example.news.aggregation.agent.execution.enums.RunStatus;
import com.example.news.aggregation.agent.execution.model.ExecutionReplaySnapshot;
import com.example.news.aggregation.agent.execution.repo.ExecutionEventLogRepository;
import com.example.news.aggregation.agent.execution.repo.ExecutionRunRepository;
import com.example.news.aggregation.agent.execution.repo.ExecutionStepRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Run 聚合服务：负责 run 创建/回放、状态迁移、计划版本切换等持久化行为。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionRunService {

    private static final String DEFAULT_TENANT_ID = "default";

    private final ExecutionRunRepository runRepository;
    private final ExecutionStepRunRepository stepRunRepository;
    private final ExecutionEventLogRepository eventLogRepository;
    private final ExecutionPersistenceProperties properties;
    private final ExecutionEventService eventService;

    public record RunAcquireResult(ExecutionRunEntity run, boolean replayed) {
    }

    public ExecutionRunEntity createOrReplayRun(String sessionId,
                                                String turnId,
                                                String requestDedupeKey,
                                                String planHash,
                                                String planId) {
        return createOrReplayRun(null, sessionId, turnId, requestDedupeKey, planHash, planId);
    }

    public ExecutionRunEntity createOrReplayRun(String tenantId,
                                                String sessionId,
                                                String turnId,
                                                String requestDedupeKey,
                                                String planHash,
                                                String planId) {
        return createOrReplayRunWithFlag(tenantId, sessionId, turnId, requestDedupeKey, planHash, planId).run();
    }

    public RunAcquireResult createOrReplayRunWithFlag(String sessionId,
                                                      String turnId,
                                                      String requestDedupeKey,
                                                      String planHash,
                                                      String planId) {
        return createOrReplayRunWithFlag(null, sessionId, turnId, requestDedupeKey, planHash, planId);
    }

    public RunAcquireResult createOrReplayRunWithFlag(String tenantId,
                                                      String sessionId,
                                                      String turnId,
                                                      String requestDedupeKey,
                                                      String planHash,
                                                      String planId) {
        ExecutionRunEntity existing = runRepository.findByRequestDedupeKey(requestDedupeKey);
        if (existing != null) {
            validatePlanHashOrThrow(existing, planHash);
            log.info("[execution-run] dedupe hit, replay existing run|runId={} |status={} |requestKey={}",
                    existing.getRunId(), existing.getStatus(), requestDedupeKey);
            return new RunAcquireResult(existing, true);
        }

        ExecutionRunEntity entity = new ExecutionRunEntity();
        entity.setRunId(UUID.randomUUID().toString().replace("-", ""));
        entity.setTenantId(resolveTenantId(tenantId));
        entity.setSessionId(sessionId);
        entity.setTurnId(turnId);
        entity.setRequestDedupeKey(requestDedupeKey);
        entity.setPlanHash(planHash);
        entity.setPlanId(planId);
        entity.setActivePlanVersion(1);
        entity.setReplanCountRun(0);
        entity.setStatus(RunStatus.PENDING.name());
        entity.setStartedAt(new Date());

        try {
            runRepository.insert(entity);
            eventService.record(
                    entity.getRunId(),
                    null,
                    "RUN_CREATED",
                    null,
                    RunStatus.PENDING.name(),
                    null,
                    "execution run created",
                    null
            );
            return new RunAcquireResult(entity, false);
        } catch (DuplicateKeyException duplicateKeyException) {
            ExecutionRunEntity conflict = runRepository.findByRequestDedupeKey(requestDedupeKey);
            if (conflict == null) {
                throw duplicateKeyException;
            }
            validatePlanHashOrThrow(conflict, planHash);
            log.warn("[execution-run] concurrent run creation conflict, replay existing run|runId={} |requestKey={}",
                    conflict.getRunId(), requestDedupeKey);
            return new RunAcquireResult(conflict, true);
        }
    }

    public String buildRequestDedupeKey(String sessionId, String turnId, String requestHash) {
        return String.join(":",
                nullToEmpty(sessionId),
                nullToEmpty(turnId),
                nullToEmpty(requestHash));
    }

    public String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(nullToEmpty(text).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("failed to generate request digest", e);
        }
    }

    public ExecutionRunEntity findByRunId(String runId) {
        return runRepository.findByRunId(runId);
    }

    /**
     * 构建 run 级回放快照，聚合 run、step_run、event_log 三类持久化数据。
     */
    public ExecutionReplaySnapshot buildReplaySnapshot(String runId) {
        return buildReplaySnapshot(runId, null);
    }

    /**
     * 构建 run 级回放快照，可指定“最近 N 条事件窗口”。
     * 说明：
     * 1. eventLimit 为空或 <=0 时，返回全量事件；
     * 2. eventLimit >0 时，按 id 倒序截取最近 N 条后再按升序输出，保证时间线可读。
     */
    public ExecutionReplaySnapshot buildReplaySnapshot(String runId, Integer eventLimit) {
        if (runId == null || runId.isBlank()) {
            return null;
        }
        ExecutionRunEntity run = runRepository.findByRunId(runId);
        if (run == null) {
            return null;
        }
        List<ExecutionEventLogEntity> events;
        if (eventLimit != null && eventLimit > 0) {
            events = eventLogRepository.listRecentByRunId(runId, eventLimit);
        } else {
            events = eventLogRepository.listByRunId(runId);
        }
        List<ExecutionStepRunEntity> stepRuns = stepRunRepository.findByRunId(runId);
        long eventCount = eventLogRepository.countByRunId(runId);
        int stepCount = stepRuns == null ? 0 : stepRuns.size();
        String timelineDigest = buildTimelineDigest(events, eventCount);
        ExecutionReplaySnapshot snapshot = ExecutionReplaySnapshot.builder()
                .run(run)
                .stepRuns(stepRuns)
                .events(events)
                .activePlanVersion(run.getActivePlanVersion())
                .stepCount(stepCount)
                .eventCount(eventCount)
                .terminalState(run.getStatus())
                .timelineDigest(timelineDigest)
                .build();
        log.info("[execution-run] 已生成运行回放快照|runId={} |stepCount={} |eventCount={} |eventWindow={} |timelineDigest={}",
                runId, stepCount, eventCount, eventLimit, timelineDigest);
        return snapshot;
    }

    public boolean markRunning(String runId, String currentStep) {
        return updateStatusWithRetry(runId, RunStatus.RUNNING.name(), currentStep, null, null, null);
    }

    public boolean markWaiting(String runId, String currentStep, String reasonCode, String message) {
        return updateStatusWithRetry(runId, RunStatus.WAITING.name(), currentStep, reasonCode, message, null);
    }

    public boolean markSucceeded(String runId) {
        return updateStatusWithRetry(runId, RunStatus.SUCCEEDED.name(), null, null, null, new Date());
    }

    public boolean markFailed(String runId, String reasonCode, String errorMessage) {
        return updateStatusWithRetry(runId, RunStatus.FAILED.name(), null, reasonCode, errorMessage, new Date());
    }

    public boolean markAborted(String runId, String reasonCode, String errorMessage) {
        return updateStatusWithRetry(runId, RunStatus.ABORTED.name(), null, reasonCode, errorMessage, new Date());
    }

    /**
     * 仅在“新计划已写入且需要激活”的场景调用。
     * 方法职责：在 CAS 语义下切换 active_plan_version，并在切换成功后扣减 run 级 replan 预算。
     */
    public boolean switchActivePlanVersionAndIncreaseReplanCount(String runId, int activePlanVersion) {
        int target = Math.max(1, activePlanVersion);
        for (int i = 0; i < 3; i++) {
            ExecutionRunEntity current = runRepository.findByRunId(runId);
            if (current == null) {
                return false;
            }
            Integer expected = current.getLockVersion() == null ? 0 : current.getLockVersion();
            int rows = runRepository.switchActivePlanVersionAndIncreaseReplanCountWithCas(runId, expected, target);
            if (rows > 0) {
                int supersededSteps = stepRunRepository.supersedePendingStepsNotInPlanVersion(runId, target);
                eventService.record(
                        runId,
                        null,
                        "RUN_REPLAN_ACTIVATED",
                        current.getStatus(),
                        current.getStatus(),
                        "active_plan_version_switched",
                        "switch active_plan_version and increase replan_count_run",
                        "{\"from\":" + (current.getActivePlanVersion() == null ? 1 : current.getActivePlanVersion())
                                + ",\"to\":" + target + "}"
                );
                if (supersededSteps > 0) {
                    eventService.record(
                            runId,
                            null,
                            "RUN_PLAN_SUPERSEDED",
                            current.getStatus(),
                            current.getStatus(),
                            "plan_superseded",
                            "supersede pending steps from old plan versions",
                            "{\"activePlanVersion\":" + target + ",\"supersededCount\":" + supersededSteps + "}"
                    );
                    log.info("[execution-run] 计划切换后已收敛旧计划未开始步骤|runId={} |activePlanVersion={} |supersededCount={}",
                            runId, target, supersededSteps);
                }
                return true;
            }
        }
        return false;
    }

    private boolean updateStatusWithRetry(String runId,
                                          String toStatus,
                                          String currentStep,
                                          String errorCode,
                                          String errorMessage,
                                          Date finishedAt) {
        for (int i = 0; i < 3; i++) {
            ExecutionRunEntity current = runRepository.findByRunId(runId);
            if (current == null) {
                return false;
            }
            Integer expected = current.getLockVersion() == null ? 0 : current.getLockVersion();
            int rows = runRepository.updateStatusWithCas(
                    runId,
                    expected,
                    toStatus,
                    currentStep,
                    errorCode,
                    errorMessage,
                    finishedAt
            );
            if (rows > 0) {
                eventService.record(
                        runId,
                        null,
                        "RUN_STATUS_CHANGED",
                        current.getStatus(),
                        toStatus,
                        errorCode,
                        "execution run status changed",
                        null
                );
                return true;
            }
        }
        return false;
    }

    private void validatePlanHashOrThrow(ExecutionRunEntity existing, String planHash) {
        if (!properties.getDedupe().isIncludePlanHash()) {
            return;
        }
        if (Objects.equals(existing.getPlanHash(), planHash)) {
            return;
        }
        eventService.record(
                existing.getRunId(),
                null,
                "RUN_PLAN_HASH_MISMATCH",
                existing.getStatus(),
                existing.getStatus(),
                "plan_hash_mismatch",
                "request dedupe key hit but planHash is different",
                null
        );
        throw new IllegalStateException("plan_hash_mismatch");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String resolveTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return DEFAULT_TENANT_ID;
        }
        return tenantId.trim();
    }

    private String buildTimelineDigest(List<ExecutionEventLogEntity> events,
                                       long totalCount) {
        if (events == null || events.isEmpty()) {
            return "无事件";
        }
        java.util.List<String> nodes = events.stream()
                .filter(Objects::nonNull)
                .map(item -> {
                    String eventType = item.getEventType() == null || item.getEventType().isBlank()
                            ? "UNKNOWN_EVENT"
                            : item.getEventType();
                    String reasonCode = item.getReasonCode();
                    return (reasonCode == null || reasonCode.isBlank())
                            ? eventType
                            : eventType + "(" + reasonCode + ")";
                })
                .limit(8)
                .toList();
        String digest = String.join(" -> ", nodes);
        if (totalCount > nodes.size()) {
            return "... -> " + digest;
        }
        return digest;
    }
}
