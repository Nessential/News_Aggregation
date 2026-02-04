package com.example.news.aggregation.agent.pipeline;

import com.example.news.aggregation.agent.domain.PipelineContext;
import com.example.news.aggregation.agent.domain.PipelineResult;
import lombok.extern.slf4j.Slf4j;

/**
 * Pipeline抽象基类
 * 定义Pipeline执行的通用契约和公共方法
 */
@Slf4j
public abstract class BasePipeline {
    
    /**
     * 执行Pipeline
     * @param context Pipeline上下文
     * @return Pipeline执行结果
     */
    public abstract PipelineResult execute(PipelineContext context);
    
    /**
     * 获取Pipeline名称
     */
    public abstract String getName();
    
    /**
     * 获取Pipeline描述
     */
    public abstract String getDescription();
    
    /**
     * 公共方法：记录Pipeline执行开始
     */
    protected void logStart(PipelineContext context) {
        log.info("Starting {} pipeline for session: {}", getName(), 
                context.getSessionState().getSessionId());
    }
    
    /**
     * 公共方法：记录Pipeline执行完成
     */
    protected void logCompletion(PipelineContext context, PipelineResult result) {
        log.info("Completed {} pipeline for session: {}, success={}, time={}ms", 
                getName(), 
                context.getSessionState().getSessionId(),
                result.getSuccess(),
                result.getExecutionTimeMs());
    }
    
    /**
     * 公共方法：创建失败结果
     */
    protected PipelineResult createFailureResult(String errorMessage) {
        return PipelineResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
