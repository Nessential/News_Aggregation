package com.example.news.aggregation.llm.springai.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天请求契约
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    
    /** 会话 ID */
    private String sessionId;
    
    /** 用户消息 */
    private String message;
    
    /** 用户 ID */
    private String userId;
}
