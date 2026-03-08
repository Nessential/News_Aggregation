package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.state.PlannerState;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * LLM Structured Output 反序列化目标：任务分解结果。
 * TaskDecompositionNode 调用 ChatClient 后将响应解析为此类，再转换为 PlannerState.SubTask 列表。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DecompositionResult {

    /** LLM 输出的子任务列表 */
    private List<SubTaskDto> tasks;

    /**
     * 转换为 PlannerState.SubTask 列表，供后续 Graph 节点使用。
     */
    public List<PlannerState.SubTask> toSubTasks() {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        return tasks.stream()
                .filter(dto -> dto != null && dto.getId() != null && !dto.getId().isBlank())
                .map(dto -> PlannerState.SubTask.builder()
                        .id(dto.getId())
                        .type(dto.getType())
                        .description(dto.getDescription())
                        .dependencies(dto.getDependencies())
                        .requiredTools(dto.getRequiredTools())
                        .parameters(dto.getParameters())
                        .build())
                .toList();
    }

    /**
     * LLM 输出的单个子任务 DTO。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubTaskDto {

        /** 任务 ID，如 task-1, task-2（LLM 自行分配） */
        private String id;

        /** 任务类型：SEARCH / RETRIEVE / ANALYZE / COMPARE / TIMELINE 等 */
        private String type;

        /** 任务自然语言描述 */
        private String description;

        /** 依赖的任务 ID 列表（空或 null 表示无依赖，可并行） */
        private List<String> dependencies;

        /** 本步骤使用的工具名列表（来自可用工具集） */
        private List<String> requiredTools;

        /** 工具调用参数（如 query、filters、topK 等） */
        private Map<String, Object> parameters;

        /** 是否可与其他无依赖关系的步骤并行执行 */
        private boolean parallelizable;
    }
}
