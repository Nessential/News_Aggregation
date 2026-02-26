package com.example.news.aggregation.agent.workflow;

import com.example.news.aggregation.llm.springai.contract.Plan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 宸ヤ綔娴佺紪鎺掑櫒銆? * 鍩轰簬 Plan 鎴栨樉寮忓伐浣滄祦鎵ц鑳藉姏閾捐矾銆? */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowOrchestrator {

    private final WorkflowRegistry workflowRegistry;
    private final PlanWorkflowAdapter planWorkflowAdapter;

    /** 浠诲姟绫诲瀷鍒伴粯璁よ兘鍔涘垪琛ㄧ殑鏄犲皠 */
    private static final Map<String, List<String>> TYPE_TOOL_MAP = Map.of(
            "SEARCH", List.of("search_news"),
            "RETRIEVE", List.of("retrieve_news"),
            "QA", List.of("llm_generate"),
            "SUMMARIZE", List.of("llm_generate"),
            "COMPARE", List.of("llm_generate"),
            "ANALYZE", List.of("llm_generate"),
            "TIMELINE", List.of("llm_generate"),
            "DEEP_DIVE", List.of("llm_generate")
    );

    /**
     * 鎵ц Plan銆?     */
    public WorkflowContext executePlan(Plan plan, WorkflowContext context) {
        if (plan == null || plan.getTasks() == null || plan.getTasks().isEmpty()) {
            log.warn("Plan is empty, skip execution.");
            return context;
        }
        String sessionId = context != null ? context.getSessionId() : "unknown";
        int taskCount = plan.getTasks() != null ? plan.getTasks().size() : 0;
        log.info("[workflow] 鎵ц璁″垝FLOW|agent|workflow=plan|step=start|sessionId={}|taskCount={}|next=杞负WorkflowDefinition",
                sessionId, taskCount);
        WorkflowDefinition workflow = planWorkflowAdapter.toWorkflowDefinition(plan, TYPE_TOOL_MAP);
        return executeWorkflow(workflow, context);
    }

    /**
     * 鎵ц鏄惧紡宸ヤ綔娴佸畾涔夈€?     */
    public WorkflowContext executeWorkflow(WorkflowDefinition workflow, WorkflowContext context) {
        if (workflow == null || workflow.getSteps() == null || workflow.getSteps().isEmpty()) {
            log.warn("Workflow is empty, skip execution.");
            return context;
        }
        List<WorkflowStep> steps = workflow.getSteps();
        boolean hasDependencies = steps.stream().anyMatch(step ->
                step != null && step.getDependsOn() != null && !step.getDependsOn().isEmpty());
        String sessionId = context != null ? context.getSessionId() : "unknown";
        log.info("鎵ц宸ヤ綔娴丗LOW|agent|workflow=explicit|step=start|sessionId={}|stepCount={}|hasDependencies={}|next=鎵ц鑳藉姏鑺傜偣",
                sessionId, steps.size(), hasDependencies);

        if (!hasDependencies) {
            // 鏃犱緷璧栨椂鎸夐『搴忔墽琛?
            for (WorkflowStep step : steps) {
                if (step == null) {
                    continue;
                }
                executeCapability(step.getCapabilityName(), step.getParameters(), context);
            }
            return context;
        }

        return executeWithDependencies(workflow, context);
    }

    /**
     * 鎵ц鎸囧畾宸ヤ綔娴?ID銆?     */
    public WorkflowContext executeWorkflow(String workflowId, WorkflowContext context) {
        if (workflowId == null || workflowId.isBlank()) {
            log.warn("workflowId is blank, skip execution.");
            return context;
        }
        String sessionId = context != null ? context.getSessionId() : "unknown";
        log.info("[workflow] 指定工作流FLOW|agent|workflow=explicit|step=lookup|sessionId={}|workflowId={}|next=执行工作流", sessionId, workflowId);
        WorkflowDefinition workflow = workflowRegistry.getWorkflow(workflowId);
        return executeWorkflow(workflow, context);
    }

    /**
     * 鍒ゆ柇宸ヤ綔娴佹槸鍚﹀瓨鍦ㄣ€?     */
    public boolean hasWorkflow(String workflowId) {
        return workflowRegistry.containsWorkflow(workflowId);
    }

    /**
     * 鍩轰簬渚濊禆鍏崇郴鎵ц宸ヤ綔娴併€?     */
    private WorkflowContext executeWithDependencies(WorkflowDefinition workflow, WorkflowContext context) {
        List<WorkflowStep> steps = workflow.getSteps();
        Map<String, WorkflowStep> stepMap = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            WorkflowStep step = steps.get(i);
            if (step == null) {
                continue;
            }
            if (step.getStepId() == null || step.getStepId().isBlank()) {
                step.setStepId("step-" + i);
            }
            stepMap.put(step.getStepId(), step);
        }

        boolean parallelizable = isParallelizable(workflow);
        boolean failFast = isFailFast(workflow);

        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        while (completed.size() + failed.size() < stepMap.size()) {
            List<WorkflowStep> readySteps = findReadySteps(stepMap, completed, failed);
            if (readySteps.isEmpty()) {
                log.warn("No runnable steps, possible dependency cycle.");
                break;
            }

            if (parallelizable) {
                executeParallel(readySteps, context, completed, failed, failFast);
            } else {
                executeSequential(readySteps, context, completed, failed, failFast);
            }

            if (failFast && !failed.isEmpty()) {
                break;
            }
        }

        context.putAttribute("workflow.completedSteps", completed);
        context.putAttribute("workflow.failedSteps", failed);
        context.putAttribute("workflow.status", failed.isEmpty() ? "SUCCESS" : "FAILED");
        return context;
    }

    private List<WorkflowStep> findReadySteps(Map<String, WorkflowStep> stepMap,
                                              List<String> completed,
                                              List<String> failed) {
        List<WorkflowStep> ready = new ArrayList<>();
        for (WorkflowStep step : stepMap.values()) {
            if (step == null) {
                continue;
            }
            String stepId = step.getStepId();
            if (completed.contains(stepId) || failed.contains(stepId)) {
                continue;
            }
            List<String> deps = step.getDependsOn();
            if (deps == null || deps.isEmpty()) {
                ready.add(step);
                continue;
            }
            boolean allMet = true;
            for (String dep : deps) {
                if (!completed.contains(dep)) {
                    allMet = false;
                    break;
                }
            }
            if (allMet) {
                ready.add(step);
            }
        }
        return ready;
    }

    private void executeParallel(List<WorkflowStep> readySteps,
                                 WorkflowContext context,
                                 List<String> completed,
                                 List<String> failed,
                                 boolean failFast) {
        List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
        for (WorkflowStep step : readySteps) {
            futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                executeStep(step, context, completed, failed, failFast);
            }));
        }
        for (java.util.concurrent.CompletableFuture<Void> future : futures) {
            try {
                future.join();
            } catch (Exception e) {
                log.warn("Parallel step execution error: {}", e.getMessage());
            }
        }
    }

    private void executeSequential(List<WorkflowStep> readySteps,
                                   WorkflowContext context,
                                   List<String> completed,
                                   List<String> failed,
                                   boolean failFast) {
        for (WorkflowStep step : readySteps) {
            executeStep(step, context, completed, failed, failFast);
            if (failFast && !failed.isEmpty()) {
                break;
            }
        }
    }

    private void executeStep(WorkflowStep step,
                             WorkflowContext context,
                             List<String> completed,
                             List<String> failed,
                             boolean failFast) {
        try {
            executeCapability(step.getCapabilityName(), step.getParameters(), context);
            completed.add(step.getStepId());
            String sessionId = context != null ? context.getSessionId() : "unknown";
            log.info("[workflow] 姝ラ瀹屾垚FLOW|agent|workflow=step|stepId={}|capability={}|sessionId={}|next=渚濊禆鍒ゆ柇/涓嬩竴姝?",
                    step.getStepId(), step.getCapabilityName(), sessionId);
        } catch (Exception e) {
            failed.add(step.getStepId());
            context.putAttribute("workflow.error", e.getMessage());
            log.warn("Step failed: {}, error={}", step.getStepId(), e.getMessage());
            if (failFast) {
                return;
            }
        }
    }

    private boolean isParallelizable(WorkflowDefinition workflow) {
        if (workflow.getMetadata() == null) {
            return false;
        }
        Object value = workflow.getMetadata().get("parallelizable");
        return value instanceof Boolean && (Boolean) value;
    }

    private boolean isFailFast(WorkflowDefinition workflow) {
        if (workflow.getMetadata() == null) {
            return false;
        }
        Object value = workflow.getMetadata().get("failFast");
        return value instanceof Boolean && (Boolean) value;
    }

    /**
     * 鎵ц鑳藉姏鑺傜偣銆?     */
    private void executeCapability(String capabilityName, Map<String, Object> parameters, WorkflowContext context) {
        if (capabilityName == null || capabilityName.isBlank()) {
            return;
        }
        String sessionId = context != null ? context.getSessionId() : "unknown";
        log.info("[workflow] 鎵ц鑳藉姏FLOW|agent|workflow=capability|step=start|sessionId={}|capability={}|next=瀵瑰簲鎵ц鍣?",
                sessionId, capabilityName);
        CapabilityExecutor executor = workflowRegistry.getExecutor(capabilityName);
        if (executor == null) {
            log.warn("Capability not found: {}", capabilityName);
            return;
        }
        executor.execute(parameters, context);
    }
}

