package com.example.news.aggregation.core.service;

import com.alicp.jetcache.Cache;
import com.alicp.jetcache.anno.CacheInvalidate;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.CacheUpdate;
import com.alicp.jetcache.anno.Cached;
import com.alicp.jetcache.anno.CreateCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Agent缓存服务示例
 * 
 * 展示JetCache的多种使用方式
 * 
 * @author Agent System
 */
@Slf4j
@Service
public class AgentCacheService {

    // ========== 1. 方法级缓存注解 ==========
    
    /**
     * 基础缓存：30分钟过期
     * 
     * 适用场景：LLM调用结果缓存
     */
    @Cached(
        name = "llm:response:",
        key = "#prompt",
        expire = 30,
        timeUnit = TimeUnit.MINUTES
    )
    public String getLLMResponse(String prompt) {
        log.info("调用LLM API: {}", prompt);
        // 模拟LLM调用
        return "LLM Response for: " + prompt;
    }
    
    /**
     * 两级缓存：本地100条 + Redis
     * 
     * 适用场景：意图识别、高频查询
     */
    @Cached(
        name = "agent:intent:",
        key = "#query",
        expire = 10,
        timeUnit = TimeUnit.MINUTES,
        cacheType = CacheType.BOTH,  // 两级缓存
        localLimit = 100  // 本地最多100条
    )
    public String analyzeIntent(String query) {
        log.info("分析意图: {}", query);
        return "QUERY";
    }
    
    /**
     * 热点数据缓存
     * 
     * 适用场景：热点数据、高QPS场景
     * 注：异步刷新功能需要JetCache 2.7+，并使用@CacheRefresh注解
     */
    @Cached(
        name = "agent:hotdata:",
        key = "#dataId",
        expire = 10,
        timeUnit = TimeUnit.MINUTES,
        cacheType = CacheType.BOTH
    )
    public String getHotData(String dataId) {
        log.info("加载热点数据: {}", dataId);
        return "Hot Data: " + dataId;
    }
    
    /**
     * 条件缓存：只缓存非空结果
     * 
     * 适用场景：工具配置、元数据查询
     */
    @Cached(
        name = "agent:tool:",
        key = "#toolName",
        expire = 1,
        timeUnit = TimeUnit.HOURS,
        condition = "#result != null"  // 只缓存非空结果
    )
    public Object getToolConfig(String toolName) {
        log.info("加载工具配置: {}", toolName);
        return toolName.equals("invalid") ? null : new Object();
    }
    
    // ========== 2. 缓存更新和删除 ==========
    
    /**
     * 更新缓存
     * 
     * 适用场景：配置更新后刷新缓存
     */
    @CacheUpdate(
        name = "agent:config:",
        key = "#configKey",
        value = "#configValue"  // 更新的值
    )
    public void updateConfig(String configKey, String configValue) {
        log.info("更新配置: {} = {}", configKey, configValue);
        // 数据库更新操作
    }
    
    /**
     * 删除缓存
     * 
     * 适用场景：配置删除后清除缓存
     */
    @CacheInvalidate(name = "agent:config:", key = "#configKey")
    public void deleteConfig(String configKey) {
        log.info("删除配置: {}", configKey);
        // 数据库删除操作
    }
    
    /**
     * 批量删除缓存
     * 
     * 适用场景：批量操作后清除缓存
     */
    @CacheInvalidate(name = "agent:config:", key = "#keys", multi = true)
    public void batchDeleteConfig(String[] keys) {
        log.info("批量删除配置: {} 个", keys.length);
        // 批量数据库操作
    }
    
    // ========== 3. 编程式缓存 ==========
    
    /**
     * 创建缓存实例（灵活控制）
     * 
     * 适用场景：需要手动控制缓存生命周期的场景
     */
    @CreateCache(
        name = "agent:custom:",
        expire = 30,
        timeUnit = TimeUnit.MINUTES,
        cacheType = CacheType.BOTH
    )
    private Cache<String, Object> customCache;
    
    /**
     * 使用编程式缓存 - computeIfAbsent模式
     */
    public Object getFromCustomCache(String key) {
        return customCache.computeIfAbsent(key, k -> {
            log.info("加载数据: {}", k);
            return "Data for " + k;
        });
    }
    
    /**
     * 使用编程式缓存 - put模式
     */
    public void putToCustomCache(String key, Object value) {
        customCache.put(key, value);
        log.info("缓存写入: key={}", key);
    }
    
    /**
     * 使用编程式缓存 - remove模式
     */
    public void removeFromCustomCache(String key) {
        customCache.remove(key);
        log.info("缓存删除: key={}", key);
    }
    
    // ========== 4. 缓存穿透防护 ==========
    
    /**
     * 缓存空值防止穿透
     * 
     * 适用场景：防止恶意查询打穿缓存
     */
    @Cached(
        name = "agent:user:",
        key = "#userId",
        expire = 5,
        timeUnit = TimeUnit.MINUTES,
        cacheNullValue = true  // 缓存null值（短时间）
    )
    public Object getUserInfo(String userId) {
        log.info("查询用户信息: {}", userId);
        // 如果用户不存在，返回null也会被缓存5分钟
        return null;
    }
    
    // ========== 5. 缓存操作示例 ==========
    
    /**
     * 检查缓存是否存在
     */
    public boolean existsInCache(String key) {
        if (customCache != null) {
            return customCache.get(key) != null;
        }
        return false;
    }
}
