package com.example.news.aggregation.agent.execution.service;

import com.example.news.aggregation.agent.execution.domain.ExecutionStepRunEntity;
import com.example.news.aggregation.agent.execution.enums.StepStatus;
import com.example.news.aggregation.agent.execution.repo.ExecutionStepRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;

/**
 * Handles WAITING step resume and rollback.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionResumeService {

    private final ExecutionStepRunRepository stepRunRepository;
    private final ExecutionEventService eventService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Move a WAITING step to PENDING with resume input.
     */
    public boolean resumeWaitingStep(String runId, String stepId, Map<String, Object> resumeInput) {
        ExecutionStepRunEntity current = stepRunRepository.findByRunIdAndStepId(runId, stepId);
        if (current == null) {
            return false;
        }
        if (!StepStatus.WAITING.name().equals(current.getStatus())) {
            return false;
        }
        String inputJson = toJson(resumeInput);
        int rows = stepRunRepository.resumeWaitingToPendingWithCas(
                runId,
                stepId,
                current.getLockVersion() == null ? 0 : current.getLockVersion(),
                inputJson
        );
        if (rows > 0) {
            eventService.record(
                    runId,
                    stepId,
                    "STEP_RESUMED",
                    StepStatus.WAITING.name(),
                    StepStatus.PENDING.name(),
                    "resume_signal",
                    "resume signal accepted, step moved to pending",
                    inputJson
            );
            return true;
        }
        return false;
    }

    /**
     * Roll back PENDING to WAITING when resume dispatch fails.
     */
    public boolean rollbackPendingToWaiting(String runId, String stepId, String reasonCode, String errorMessage) {
        ExecutionStepRunEntity current = stepRunRepository.findByRunIdAndStepId(runId, stepId);
        if (current == null || !StepStatus.PENDING.name().equals(current.getStatus())) {
            return false;
        }
        int rows = stepRunRepository.markTerminalWithCas(
                runId,
                stepId,
                current.getLockVersion() == null ? 0 : current.getLockVersion(),
                StepStatus.WAITING.name(),
                reasonCode,
                "RESUME_DISPATCH_FAILED",
                errorMessage,
                new Date()
        );
        if (rows > 0) {
            eventService.record(
                    runId,
                    stepId,
                    "STEP_RESUME_ROLLBACK",
                    StepStatus.PENDING.name(),
                    StepStatus.WAITING.name(),
                    reasonCode,
                    "resume dispatch failed, step rolled back to waiting",
                    null
            );
            return true;
        }
        return false;
    }

    private String toJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[execution-resume] failed to serialize resume input|error={}", e.getMessage());
            return null;
        }
    }
}
