package com.example.news.aggregation.agent.execution.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.news.aggregation.datasource.domain.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 工具熔断状态实体（tool + capability）。
 */
@Getter
@Setter
@TableName("agent_tool_circuit_state")
public class ToolCircuitStateEntity extends BaseEntity {

    private String toolName;
    private String capability;
    private String state;
    private Date openUntil;
    private String halfOpenOwner;
    private Date ownerLeaseUntil;
    private String lastReasonCode;
}
