package com.example.news.aggregation.rpc.contract;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Generator
 * Graph
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratorRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /**  */
    private String query;

    /** /?*/
    private String queryInterpretation;

    /**  */
    private String taskFamily;

    /** SEMANTICEYWORDYBRIDONE */
    private String retrievalMode;

    /**  */
    private List<RetrievalResult> evidence;
}
