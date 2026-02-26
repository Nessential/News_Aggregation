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
        String sessionId = state.getSessionId() != null ? state.getSessionId() : "unknown";

        boolean needsTimeRange = "SUMMARY".equals(taskFamily)
                || "TIMELINE".equals(taskFamily)
                || "DEEP_DIVE".equals(taskFamily)
                || "COMPARE".equals(taskFamily);

        boolean hasTimeRange = params != null && params.containsKey("time_range");

        if (needsTimeRange && !hasTimeRange) {
            state.setNeedsClarification(true);
            state.setClarificationQuestion("请提供时间范围（例如最近7天、30天、一个月）。");
            // 流程日志：缺少必要槽位，进入澄清
            log.info("完整性检查-需要澄清FLOW|router|node=completeness_check|decision=need_clarification|sessionId={}|taskFamily={}|reason=missing_time_range|next=END(等待澄清)",
                    sessionId, taskFamily);
        } else {
            state.setNeedsClarification(false);
            log.info("完整性检查-通过FLOW|router|node=completeness_check|decision=complete|sessionId={}|taskFamily={}|next=END",
                    sessionId, taskFamily);
        }

        return state;
    }
}
