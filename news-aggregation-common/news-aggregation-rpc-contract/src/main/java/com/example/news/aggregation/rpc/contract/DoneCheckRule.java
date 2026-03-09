package com.example.news.aggregation.rpc.contract;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ? */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoneCheckRule implements Serializable {
    private static final long serialVersionUID = 1L;
    private List<String> requiredFields;
    private Integer minEvidenceCount;
    private String expression;
}

