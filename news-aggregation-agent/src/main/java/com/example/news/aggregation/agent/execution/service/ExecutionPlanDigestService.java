package com.example.news.aggregation.agent.execution.service;

import com.example.news.aggregation.llm.springai.contract.ExecutionPlan;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Computes a stable digest for normalized execution plan content.
 */
@Service
public class ExecutionPlanDigestService {

    private final ObjectMapper objectMapper;

    public ExecutionPlanDigestService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.objectMapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
    }

    public String sha256Hex(ExecutionPlan plan) {
        if (plan == null) {
            return "";
        }
        try {
            String normalized = objectMapper.writeValueAsString(plan);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("failed to compute plan hash", e);
        }
    }
}

