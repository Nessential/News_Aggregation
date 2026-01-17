package com.example.news.aggregation.core.service;

import com.example.news.aggregation.core.domain.FSMState;
import com.example.news.aggregation.core.domain.Message;
import com.example.news.aggregation.core.domain.SessionState;

import java.util.List;

/**
 * Session管理接口
 */
public interface SessionManager {

    /**
     * 创建新会话
     */
    SessionState createSession(String userId);

    /**
     * 获取会话（不加锁，只读）
     */
    SessionState getSession(String sessionId);

    /**
     * 更新会话（带分布式锁 + 乐观锁）
     */
    void updateSession(SessionState sessionState);

    /**
     * 追加消息到会话
     */
    void appendMessage(String sessionId, Message message);

    /**
     * 更新FSM状态
     */
    void updateFSMState(String sessionId, FSMState newState);

    /**
     * 获取对话历史
     */
    List<Message> getHistory(String sessionId);

    /**
     * 删除会话
     */
    void deleteSession(String sessionId);

    /**
     * 会话是否存在
     */
    boolean exists(String sessionId);
}