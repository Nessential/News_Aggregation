package com.example.news.aggregation.agent.workflow.executor;

import com.example.news.aggregation.agent.tool.RerankTool;
import com.example.news.aggregation.agent.tool.dto.RetrievalResult;
import com.example.news.aggregation.agent.workflow.CapabilityExecutor;
import com.example.news.aggregation.agent.workflow.CapabilityMetadata;
import com.example.news.aggregation.agent.workflow.WorkflowContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 重排能力执行器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankExecutor implements CapabilityExecutor {

    private final RerankTool rerankTool;

    @Override
    public String capabilityName() {
        return "rerank_results";
    }

    @Override
    public CapabilityMetadata metadata() {
        return CapabilityMetadata.builder()
                .name("rerank_results")
                .version("v1")
                .description("基于 MMR 的结果重排")
                .timeoutMs(2000L)
                .costLevel("LOW")
                .permissionScope("PUBLIC")
                .build();
    }

    @Override
    public Object execute(Map<String, Object> parameters, WorkflowContext context) {
        int topK = parameters != null && parameters.get("topK") instanceof Number
                ? ((Number) parameters.get("topK")).intValue()
                : 5;
        double lambda = parameters != null && parameters.get("lambda") instanceof Number
                ? ((Number) parameters.get("lambda")).doubleValue()
                : 0.7;
        String sessionId = context != null ? context.getSessionId() : "unknown";
        log.info("[流程][重排结果] 开始执行|sessionId={} |topK={} |lambda={} |reason=提升多样性与相关性",
                sessionId, topK, lambda);

        List<RetrievalResult> results = rerankTool.mmrRerank(context.getEvidence(), topK, lambda);
        context.setEvidence(results);
        log.info("[流程][重排结果] 执行完成|sessionId={} |resultCount={} |next=生成",
                sessionId, results.size());

        Map<String, Object> output = ToolOutputEnvelope.items(capabilityName(), results, "execution-plan/1.0");
        log.info("[流程][重排结果] 输出已对象化|sessionId={} |capability={} |count={} |schemaVersion={}",
                sessionId, capabilityName(), output.get("count"), output.get("schemaVersion"));
        return output;
    }
}
