package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.state.RouterState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 完整性检查节点。
 * 判断是否缺少必要约束，并给出澄清问题。
 */
@Slf4j
@Component
public class CompletenessCheckNode {

    /**
     * 执行完整性检查。
     *
     * @param state Router 状态
     * @return 更新后的状态
     */
    public RouterState execute(RouterState state) {
        state.incrementStep();

        Map<String, Object> params = state.getParams();
        String taskFamily = state.getTaskFamily();
        String intentScope = state.getIntentScope();
        String sessionId = state.getSessionId() != null ? state.getSessionId() : "unknown";

        if ("NON_NEWS".equalsIgnoreCase(intentScope)) {
            state.setNeedsClarification(false);
            log.info("完整性检查-非新闻跳过FLOW|router|node=completeness_check|decision=skip_non_news|sessionId={}|next=END",
                    sessionId);
            return state;
        }

        boolean needsTimeRange = "SUMMARY".equals(taskFamily)
                || "TIMELINE".equals(taskFamily)
                || "DEEP_DIVE".equals(taskFamily)
                || "COMPARE".equals(taskFamily);

        boolean hasTimeRange = hasTimeRange(params);

        if (needsTimeRange && !hasTimeRange) {
            state.setNeedsClarification(true);
            state.setClarificationQuestion("请补充时间范围，例如：最近7天，或指定起止日期（2025-01-01 至 2025-01-07）。");
            // 流程日志：缺少必要槽位，进入澄清
            log.info("完整性检查-需要澄清FLOW|router|node=completeness_check|decision=need_clarification|sessionId={}|taskFamily={}|reason=missing_time_range|next=END(等待澄清)",
                    sessionId, taskFamily);
        } else {
            state.setNeedsClarification(false);
            log.info("[链路最终] 完整性检查-通过FLOW|router|node=completeness_check|decision=complete|sessionId={}|taskFamily={}|next=END",
                    sessionId, taskFamily);
        }

        return state;
    }

    private boolean hasTimeRange(Map<String, Object> params) {
        if (params == null) {
            return false;
        }
        return params.containsKey("time_range")
                || params.containsKey("timeRange")
                || (params.containsKey("startDate") && params.containsKey("endDate"))
                || (params.containsKey("start_date") && params.containsKey("end_date"));
    }
}