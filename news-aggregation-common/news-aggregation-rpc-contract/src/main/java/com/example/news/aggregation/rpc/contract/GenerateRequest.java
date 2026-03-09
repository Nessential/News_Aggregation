package com.example.news.aggregation.rpc.contract;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /** ?*/
    private String prompt;

    /**  */
    private String model;

    /**  */
    private Double temperature;
}