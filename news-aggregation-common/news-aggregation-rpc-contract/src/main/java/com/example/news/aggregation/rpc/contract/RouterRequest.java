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
 * ? */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouterRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /** ID */
    private String sessionId;

    /**  */
    private String query;

    /**  */
    private List<String> history;

    /** ?/ */
    private Map<String, Object> constraints;
}