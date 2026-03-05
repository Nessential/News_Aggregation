package com.example.news.aggregation.agent.execution.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.news.aggregation.datasource.domain.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 执行运行主记录。
 */
@Getter
@Setter
@TableName("agent_execution_run")
public class ExecutionRunEntity extends BaseEntity {

    private String runId;
    private String sessionId;
    private String turnId;
    private String requestDedupeKey;
    private String planHash;
    private String planId;
    private String status;
    private String currentStep;
    private String errorCode;
    private String errorMessage;
    private Date startedAt;
    private Date finishedAt;
}
