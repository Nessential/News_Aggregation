package com.example.news.aggregation.agent.domain.chat;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户最近会话摘要（用于会话列表兜底查询）。
 */
@Getter
@Setter
public class UserSessionSummary {

    /** 会话ID */
    private String sessionId;

    /** 用户ID */
    private String userId;

    /** 最近消息时间 */
    private LocalDateTime latestAt;
}

