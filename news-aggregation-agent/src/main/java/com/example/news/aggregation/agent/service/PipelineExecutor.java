package com.example.news.aggregation.agent.service;

import com.example.news.aggregation.agent.domain.PipelineContext;
import com.example.news.aggregation.agent.domain.PipelineResult;
import com.example.news.aggregation.agent.enums.TaskFamily;
import com.example.news.aggregation.agent.pipeline.BasePipeline;
import com.example.news.aggregation.agent.pipeline.QAPipeline;
import com.example.news.aggregation.agent.pipeline.SearchPipeline;
import com.example.news.aggregation.agent.pipeline.SummaryPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Pipeline执行器
 * 根据TaskFamily选择并执行对应的Pipeline
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineExecutor {
    
    private final QAPipeline qaPipeline;
    private final SummaryPipeline summaryPipeline;
    private final SearchPipeline searchPipeline;
    
    /**
     * 执行Pipeline
     */
    public PipelineResult execute(PipelineContext context) {
        TaskFamily taskFamily = extractTaskFamily(context);
        BasePipeline pipeline = selectPipeline(taskFamily);
        
        if (pipeline == null) {
            log.error("No pipeline found for task family: {}", taskFamily);
            return createFailureResult("Unsupported task type: " + taskFamily);
        }
        
        log.info("Executing {} pipeline for session: {}", 
                pipeline.getName(), 
                context.getSessionState().getSessionId());
        
        try {
            return pipeline.execute(context);
        } catch (Exception e) {
            log.error("Pipeline execution failed", e);
            return createFailureResult("Pipeline execution error: " + e.getMessage());
        }
    }
    
    /**
     * 根据TaskFamily选择Pipeline
     */
    private BasePipeline selectPipeline(TaskFamily taskFamily) {
        return switch (taskFamily) {
            case QA -> qaPipeline;
            case SUMMARY, COMPARE, TIMELINE, DEEP_DIVE -> summaryPipeline;
            case SEARCH, MONITORING -> searchPipeline;
            default -> {
                log.warn("Unknown task family: {}, falling back to QA pipeline", taskFamily);
                yield qaPipeline;
            }
        };
    }
    
    /**
     * 从上下文提取TaskFamily
     */
    private TaskFamily extractTaskFamily(PipelineContext context) {
        if (context.getRouterResult() != null && context.getRouterResult().getTaskFamily() != null) {
            return TaskFamily.valueOf(context.getRouterResult().getTaskFamily());
        }
        // 默认使用QA
        log.warn("No task family found in context, defaulting to QA");
        return TaskFamily.QA;
    }
    
    /**
     * 创建失败结果
     */
    private PipelineResult createFailureResult(String errorMessage) {
        return PipelineResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
