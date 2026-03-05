package com.example.news.aggregation.agent.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 事件分页响应体（cursor = last_event_id）。
 */
@Data
@Builder
public class ExecutionEventPageResponse {

    private String runId;
    private Long cursor;
    private Integer limit;
    private Long nextCursor;
    private boolean hasMore;
    private List<ExecutionReplayResponse.EventView> events;
}

