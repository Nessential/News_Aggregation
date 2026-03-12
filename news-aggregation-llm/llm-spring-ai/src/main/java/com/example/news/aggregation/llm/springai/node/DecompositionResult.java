package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.state.PlannerState;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 任务分解结果。
 * <p>
 * 模型在规划阶段输出的 JSON 会先反序列化为该对象，
 * 再转换为系统内部使用的 {@link PlannerState.SubTask} 列表。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DecompositionResult {

    /**
     * 模型输出的子任务列表。
     */
    private List<SubTaskDto> tasks;

    /**
     * 将模型输出的任务列表转换为内部子任务对象。
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
     * 单个子任务的数据结构。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubTaskDto {

        /**
         * 任务 ID，例如 task-1。
         */
        private String id;

        /**
         * 任务类型，例如 SEARCH、RETRIEVE、RERANK、ANALYZE。
         */
        private String type;

        /**
         * 任务描述。
         */
        private String description;

        /**
         * 依赖任务 ID 列表。
         */
        private List<String> dependencies;

        /**
         * 当前步骤要使用的工具列表。
         */
        private List<String> requiredTools;

        /**
         * 工具调用参数。
         */
        private Map<String, Object> parameters;

        /**
         * 当前步骤是否允许并行执行。
         */
        private boolean parallelizable;
    }
}
