package com.example.news.aggregation.agent.service;

import com.example.news.aggregation.agent.domain.AgentResponse;
import com.example.news.aggregation.agent.dto.ChatRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 兼容层：保留旧的 AgentOrchestrator 名称，内部委托给 LLMOrchestrator。
 */
@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final LLMOrchestrator llmOrchestrator;

    /**
     * 处理聊天请求。
     */
    public AgentResponse handleChat(ChatRequest request) {
        return llmOrchestrator.handleChat(request);
    }
}