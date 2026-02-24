package com.example.news.aggregation.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Agent应用启动类
 * 
 * Agent模块职责：
 * - 会话管理 (Session State)
 * - 对话流程控制 (FSM)
 * - Pipeline执行 (检索→融合→生成)
 * - 工具调用 (Tools)
 * - 响应组装 (ActionComposer)
 * 
 * @author system
 */
@SpringBootApplication(scanBasePackages = {
    "com.example.news.aggregation.agent",
    "com.example.news.aggregation.vector",
    "com.example.news.aggregation.es",
    "com.example.news.aggregation.embedding",
    "com.example.news.aggregation.cache",
    "com.example.news.aggregation.base",
        // 不扫描 job 包，避免加载 XxlJobConfig
})
public class AgentApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
