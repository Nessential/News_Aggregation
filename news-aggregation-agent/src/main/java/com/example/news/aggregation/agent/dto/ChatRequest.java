package com.example.news.aggregation.agent.dto;

import com.example.news.aggregation.agent.domain.Constraints;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ???? DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    private String sessionId;
    private String userId;
    private String query;
    private Constraints constraints;
}
