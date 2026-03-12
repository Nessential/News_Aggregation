package com.example.news.aggregation.llm.springai.planner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 规划阶段专用的工具声明提供器。
 * <p>
 * 这些方法只用于向模型暴露工具名称、参数结构和适用场景，
 * 不在规划阶段执行真实检索或重排逻辑。
 * 如果模型误调用这些工具，会收到一条明确的提示信息，
 * 让模型回到“只生成计划”的职责范围内。
 */
@Slf4j
@Component
public class PlannerToolSchemaProvider {

    @Tool(
            name = "search_news",
            description = "关键词检索新闻。适合精确实体、明确事件、明确时间范围的查询规划。常用参数：query、topK、filters。规划阶段只可用于理解能力边界，不应真实调用。"
    )
    public String searchNews(String query, Integer topK, Map<String, Object> filters) {
        log.warn("规划阶段意外触发 search_news 工具调用，已拒绝执行。query={}, topK={}, filters={}",
                query, topK, filters);
        return "当前处于规划阶段，只允许生成执行计划，不允许直接执行 search_news。请根据该工具的名称与参数结构输出计划 JSON。";
    }

    @Tool(
            name = "retrieve_news",
            description = "语义检索新闻。适合抽象表达、同义改写、主题归纳类查询规划。常用参数：query、topK、minScore、mode、filters。规划阶段只可用于理解能力边界，不应真实调用。"
    )
    public String retrieveNews(String query, Integer topK, Double minScore, String mode, Map<String, Object> filters) {
        log.warn("规划阶段意外触发 retrieve_news 工具调用，已拒绝执行。query={}, topK={}, minScore={}, mode={}, filters={}",
                query, topK, minScore, mode, filters);
        return "当前处于规划阶段，只允许生成执行计划，不允许直接执行 retrieve_news。请根据该工具的名称与参数结构输出计划 JSON。";
    }

    @Tool(
            name = "hybrid_retrieve_news",
            description = "混合检索新闻。适合默认问答、多实体、多约束、既要语义覆盖又要精确命中的查询规划。常用参数：query、topK、minScore、filters。规划阶段只可用于理解能力边界，不应真实调用。"
    )
    public String hybridRetrieveNews(String query, Integer topK, Double minScore, Map<String, Object> filters) {
        log.warn("规划阶段意外触发 hybrid_retrieve_news 工具调用，已拒绝执行。query={}, topK={}, minScore={}, filters={}",
                query, topK, minScore, filters);
        return "当前处于规划阶段，只允许生成执行计划，不允许直接执行 hybrid_retrieve_news。请根据该工具的名称与参数结构输出计划 JSON。";
    }

    @Tool(
            name = "rerank_results",
            description = "对检索结果做重排。适合在召回结果较多、需要提高多样性或去重时加入计划。常用参数：topK、lambda。规划阶段只可用于理解能力边界，不应真实调用。"
    )
    public String rerankResults(Integer topK, Double lambda) {
        log.warn("规划阶段意外触发 rerank_results 工具调用，已拒绝执行。topK={}, lambda={}", topK, lambda);
        return "当前处于规划阶段，只允许生成执行计划，不允许直接执行 rerank_results。请根据该工具的名称与参数结构输出计划 JSON。";
    }
}
