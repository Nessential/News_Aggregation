package com.example.news.aggregation.llm.springai.node;

import com.example.news.aggregation.llm.springai.state.RouterState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 槽位提取节点。
 * 提取时间范围/语言/分类等参数。
 */
@Slf4j
@Component
public class SlotExtractionNode {

    /**
     * 执行槽位提取。
     *
     * @param state Router 状态
     * @return 更新后的状态
     */
    public RouterState execute(RouterState state) {
        state.incrementStep();

        String query = state.getResolvedQuery() != null ? state.getResolvedQuery() : state.getQuery();
        String sessionId = state.getSessionId() != null ? state.getSessionId() : "unknown";
        // 流程日志：进入槽位提取
        log.info("进入槽位提取FLOW|router|node=slot_extract|step=start|sessionId={}|query={}|next=completeness_check",
                sessionId, truncate(query, 200));

        if (query == null) {
            log.info("槽位提取跳过-空查询FLOW|router|node=slot_extract|decision=skip_null_query|sessionId={}|next=completeness_check",
                    sessionId);
            return state;
        }

        Map<String, Object> params = state.getParams() != null ? new HashMap<>(state.getParams()) : new HashMap<>();

        // TODO 简化的时间范围识别
        String lower = query.toLowerCase(Locale.ROOT);
        if (lower.contains("最近7天") || lower.contains("近7天") || lower.contains("7天内") || lower.contains("一周")) {
            params.put("time_range", "7d");
        } else if (lower.contains("最近30天") || lower.contains("近30天") || lower.contains("30天内") || lower.contains("一个月")) {
            params.put("time_range", "30d");
        } else if (lower.contains("最近三个月") || lower.contains("近三个月") || lower.contains("3个月")) {
            params.put("time_range", "90d");
        }

        // TODO 简化语言识别
        if (lower.contains("英文") || lower.contains("英语") || lower.contains("english")) {
            params.put("language", "en");
        } else if (lower.contains("中文") || lower.contains("chinese")) {
            params.put("language", "zh");
        }

        state.setParams(params);
        log.info("槽位提取完成FLOW|router|node=slot_extract|decision=extracted|sessionId={}|params={}|next=completeness_check",
                sessionId, params);
        return state;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
