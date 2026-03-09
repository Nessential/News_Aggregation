package com.example.news.aggregation.rpc.contract.news;

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
public class IdsRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    // ID
    private List<Long> ids;
}
