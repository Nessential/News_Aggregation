package com.example.news.aggregation.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册中心
 * 管理所有Tool实例，提供Tool查找功能
 */
@Slf4j
@Component
public class ToolRegistry {
    
    private final Map<String, Tool<?, ?>> tools = new HashMap<>();
    
    public ToolRegistry(RetrieveTool retrieveTool, SearchTool searchTool, RerankTool rerankTool) {
        registerTool(retrieveTool);
        registerTool(searchTool);
        registerTool(rerankTool);
        log.info("ToolRegistry initialized with {} tools", tools.size());
    }
    
    /**
     * 注册工具
     */
    public void registerTool(Tool<?, ?> tool) {
        String name = tool.getName();
        tools.put(name, tool);
        log.debug("Registered tool: {}", name);
    }
    
    /**
     * 获取工具
     */
    @SuppressWarnings("unchecked")
    public <I, O> Optional<Tool<I, O>> getTool(String name) {
        Tool<?, ?> tool = tools.get(name);
        if (tool == null) {
            log.warn("Tool not found: {}", name);
            return Optional.empty();
        }
        return Optional.of((Tool<I, O>) tool);
    }
    
    /**
     * 获取所有工具名称
     */
    public Map<String, String> getAllToolDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        tools.forEach((name, tool) -> descriptions.put(name, tool.getDescription()));
        return descriptions;
    }
    
    /**
     * 检查工具是否存在
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }
}
