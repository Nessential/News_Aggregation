package com.example.news.aggregation.agent.fsm;

import com.example.news.aggregation.agent.domain.SessionState;
import com.example.news.aggregation.agent.tool.dto.RetrievalResult;
import com.example.news.aggregation.llm.springai.contract.ExecutionPlan;
import com.example.news.aggregation.llm.springai.contract.GeneratorDraft;
import com.example.news.aggregation.llm.springai.contract.RouterResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * FSM 上下文。
 * 用于状态迁移判断的关键信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FSMContext {

    /** 会话状态 */
    private SessionState sessionState;

    /** Router 结果 */
    private RouterResult routerResult;

    /** 计划结果 */
    private ExecutionPlan executionPlan;

    /** 生成草稿 */
    private GeneratorDraft draft;

    /** 候选文档 ID */
    private List<Long> candidateIds;

    /** 证据列表 */
    private List<RetrievalResult> evidence;

    /** 证据数量 */
    private Integer evidenceCount;

    /** 重试次数 */
    private Integer retryCount;

    /** 是否需要澄清 */
    private Boolean needsClarification;

    /** 是否直接回答（无需检索） */
    private Boolean directAnswer;

    /** 是否需要确认（写操作） */
    private Boolean requiresConfirmation;

    /** 是否走异步分发 */
    private Boolean dispatchAsync;

    /** 扩展元数据 */
    private Map<String, Object> metadata;
}
