package com.example.news.aggregation.agent.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工作流与能力注册表。
 * 统一管理可用能力与工作流定义。
 */
@Slf4j
@Component
public class WorkflowRegistry {

    private final Map<String, CapabilityExecutor> executors = new ConcurrentHashMap<>();
    private final Map<String, WorkflowDefinition> workflows = new ConcurrentHashMap<>();

    public WorkflowRegistry(List<CapabilityExecutor> executorList) {
        if (executorList != null) {
            for (CapabilityExecutor executor : executorList) {
                registerExecutor(executor);
            }
        }
    }

    /** 注册能力执行器 */
    public void registerExecutor(CapabilityExecutor executor) {
        executors.put(executor.capabilityName(), executor);
        log.info("Registered capability: {}", executor.capabilityName());
    }

    /** 获取能力执行器 */
    public CapabilityExecutor getExecutor(String name) {
        return executors.get(name);
    }

    /** 列出能力元数据 */
    public List<CapabilityMetadata> listCapabilities() {
        return executors.values().stream()
                .map(CapabilityExecutor::metadata)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /** 注册工作流 */
    public void registerWorkflow(WorkflowDefinition workflow) {
        workflows.put(workflow.getId(), workflow);
        log.info("Registered workflow: {}", workflow.getId());
    }

    /** 获取工作流 */
    public WorkflowDefinition getWorkflow(String workflowId) {
        return workflows.get(workflowId);
    }

    /** 列出工作流 */
    public List<WorkflowDefinition> listWorkflows() {
        return new ArrayList<>(workflows.values());
    }

    /** 判断工作流是否存在 */
    public boolean containsWorkflow(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return false;
        }
        return workflows.containsKey(workflowId);
    }
}