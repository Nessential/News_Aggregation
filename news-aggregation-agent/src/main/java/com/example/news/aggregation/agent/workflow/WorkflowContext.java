package com.example.news.aggregation.agent.workflow;

import com.example.news.aggregation.agent.tool.dto.RetrievalResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    /** Planner 追踪ID。 */
    private String plannerTraceId;

    /** 用户查询。 */
    private String query;

    /** 查询理解/规范化描述（意图识别阶段的改写，用于生成阶段）。 */
    private String queryInterpretation;

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
        if (this.evidence == null) {
            this.evidence = new ArrayList<>();
        }

        // 按 articleId 去重合并：同一篇文章可能来自 ES/Qdrant/扩展关键词的多次检索
        Map<Long, RetrievalResult> merged = new LinkedHashMap<>();
        List<RetrievalResult> noId = new ArrayList<>();

        for (RetrievalResult r : this.evidence) {
            if (r == null) {
                continue;
            }
            if (r.getArticleId() == null) {
                noId.add(r);
                continue;
            }
            merged.merge(r.getArticleId(), r, WorkflowContext::pickBetterEvidence);
        }
        for (RetrievalResult r : results) {
            if (r == null) {
                continue;
            }
            if (r.getArticleId() == null) {
                noId.add(r);
                continue;
            }
            merged.merge(r.getArticleId(), r, WorkflowContext::pickBetterEvidence);
        }

        this.evidence.clear();
        this.evidence.addAll(merged.values());
        this.evidence.addAll(noId);
    }

    public void putAttribute(String key, Object value) {
        this.attributes.put(key, value);
    }

    private static RetrievalResult pickBetterEvidence(RetrievalResult a, RetrievalResult b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        // 优先保留 fullContent 更“有料”的条目（给后续 LLM content 拼装用）
        boolean aHasFull = a.getFullContent() != null && !a.getFullContent().isBlank();
        boolean bHasFull = b.getFullContent() != null && !b.getFullContent().isBlank();
        if (aHasFull != bHasFull) {
            return bHasFull ? b : a;
        }

        int aSnippetLen = a.getMatchedSnippet() != null ? a.getMatchedSnippet().trim().length() : 0;
        int bSnippetLen = b.getMatchedSnippet() != null ? b.getMatchedSnippet().trim().length() : 0;
        if (aSnippetLen != bSnippetLen) {
            return bSnippetLen > aSnippetLen ? b : a;
        }

        double aScore = a.getScore() != null ? a.getScore() : 0.0;
        double bScore = b.getScore() != null ? b.getScore() : 0.0;
        if (aScore != bScore) {
            return bScore > aScore ? b : a;
        }

        // 尽量补齐元数据
        RetrievalResult winner = a;
        if ((winner.getMetadata() == null || winner.getMetadata().isBlank())
                && b.getMetadata() != null && !b.getMetadata().isBlank()) {
            winner.setMetadata(b.getMetadata());
        }
        if ((winner.getMatchedSnippet() == null || winner.getMatchedSnippet().isBlank())
                && b.getMatchedSnippet() != null && !b.getMatchedSnippet().isBlank()) {
            winner.setMatchedSnippet(b.getMatchedSnippet());
        }
        if ((winner.getFullContent() == null || winner.getFullContent().isBlank())
                && b.getFullContent() != null && !b.getFullContent().isBlank()) {
            winner.setFullContent(b.getFullContent());
        }
        return winner;
    }
}
