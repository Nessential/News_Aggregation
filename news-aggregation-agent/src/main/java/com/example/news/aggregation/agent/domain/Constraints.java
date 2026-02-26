package com.example.news.aggregation.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户约束条件。
 * 定义检索的时间范围、主题、来源等过滤条件。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Constraints implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 时间范围 - 开始时间 */
    private LocalDateTime timeRangeStart;

    /** 时间范围 - 结束时间 */
    private LocalDateTime timeRangeEnd;

    /** 主题过滤（可多选） */
    private List<String> topics;

    /** 来源过滤（可多选） */
    private List<String> sources;

    /** 关键词过滤 */
    private List<String> keywords;

    /** 语言过滤（默认中文） */
    private String language;

    /** 最大检索数量 */
    private Integer maxResults;

    /**
    * 检查是否为空约束。
    */
    public boolean isEmpty() {
        return timeRangeStart == null && timeRangeEnd == null
                && (topics == null || topics.isEmpty())
                && (sources == null || sources.isEmpty())
                && (keywords == null || keywords.isEmpty())
                && language == null
                && maxResults == null;
    }

    /**
     * 转换为 Map 便于跨服务传递。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("timeRangeStart", timeRangeStart);
        map.put("timeRangeEnd", timeRangeEnd);
        map.put("topics", topics);
        map.put("sources", sources);
        map.put("keywords", keywords);
        map.put("language", language);
        map.put("maxResults", maxResults);
        return map;
    }
}