package com.example.news.aggregation.agent.workflow;

import com.example.news.aggregation.agent.tool.dto.RetrievalResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流上下文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowContext {

    /** 会话ID。 */
    private String sessionId;

    /** 运行ID（第三周持久化执行主键）。 */
    private String runId;

    /** 当前worker标识。 */
    private String workerId;

    /** 是否恢复模式执行。 */
    private Boolean recoveryMode;

    /** 用户查询。 */
    private String query;

    /** 任务类型。 */
    private String taskFamily;

    /** 证据列表。 */
    @Builder.Default
    private List<RetrievalResult> evidence = new ArrayList<>();

    /** 扩展属性。 */
    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();

    public void addEvidence(List<RetrievalResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        this.evidence.addAll(results);
    }

    public void putAttribute(String key, Object value) {
        this.attributes.put(key, value);
    }
}
