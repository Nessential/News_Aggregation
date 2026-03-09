package com.example.news.aggregation.rpc.contract;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Router
 * ?
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouterResult implements Serializable {
    private static final long serialVersionUID = 1L;

    /** AUMMARYOMPAREIMELINEEEP_DIVE */
    private String taskFamily;

    /** SEMANTICEYWORDYBRIDONE */
    private String retrievalMode;

    /** OWEDIUMIGH */
    private String riskLevel;

    /** ?*/
    private Boolean needsClarification;

    /**  */
    private String clarificationQuestion;

    /** // */
    private Map<String, Object> params;

    /** NEWS/NON_NEWS */
    private String intentScope;

    /**  */
    private Double intentConfidence;

    /** ?*/
    private String intentReason;

    /** ?*/
    private Double taskConfidence;

    /**  */
    private String taskReason;

    /** /// */
    private List<String> entities;

    /** /?'?? */
    private String queryInterpretation;

    /**
     * QA
     * outer
     */
    public static RouterResult defaultQA() {
        return RouterResult.builder()
                .taskFamily("QA")
                .retrievalMode("HYBRID")
                .riskLevel("LOW")
                .needsClarification(false)
                .intentScope("NEWS")
                .intentConfidence(0.0)
                .intentReason("default")
                .build();
    }
}
