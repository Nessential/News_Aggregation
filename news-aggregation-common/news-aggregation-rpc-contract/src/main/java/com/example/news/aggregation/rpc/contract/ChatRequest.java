package com.example.news.aggregation.rpc.contract;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /** ID */
    private String sessionId;

    /**  */
    private String message;

    /** ID */
    private String userId;
}