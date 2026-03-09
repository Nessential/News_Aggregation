package com.example.news.aggregation.rpc.contract;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    /** ID */
    private String sessionId;

    /** LLM?*/
    private String answer;

    /** A/SUMMARY/COMPARE  */
    private String taskFamily;

    /**  */
    private List<Source> sources;

    /** ?*/
    private Long timestamp;

    /**
     * 
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Source implements Serializable {
        private static final long serialVersionUID = 1L;
        /** ID */
        private String id;

        /**  */
        private String title;

        /**  */
        private String url;

        /** ?*/
        private Double relevance;
    }
}