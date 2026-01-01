package com.example.news.aggregation.job.handler;
/**
 * Job Handler 基类
 * 预留给 XXL-Job 集成使用
 *
 * @author NewsAggregation
 */
public abstract class BaseJobHandler {

    /**
     * 执行任务
     *
     * @param param 任务参数
     * @throws Exception 执行异常
     */
    public abstract void execute(String param) throws Exception;
}
