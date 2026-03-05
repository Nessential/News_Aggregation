package com.example.news.aggregation.agent.execution.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * HTTP implementation for remote side-effect verification.
 * Only whitelisted hosts are allowed for providerTrace URLs.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.agent.execution.effect-query", name = "enabled", havingValue = "true")
public class HttpEffectQueryGateway implements EffectQueryGateway {

    private final HttpClient httpClient;
    private final long readTimeoutMs;
    private final List<String> allowedHosts;

    public HttpEffectQueryGateway(
            @Value("${app.agent.execution.effect-query.connect-timeout-ms:1500}") long connectTimeoutMs,
            @Value("${app.agent.execution.effect-query.read-timeout-ms:2000}") long readTimeoutMs,
            @Value("${app.agent.execution.effect-query.allowed-hosts:}") List<String> allowedHosts
    ) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(200, connectTimeoutMs)))
                .build();
        this.readTimeoutMs = Math.max(200, readTimeoutMs);
        this.allowedHosts = allowedHosts == null ? List.of() : allowedHosts;
    }

    @Override
    public EffectQueryResult query(String runId, String stepId, String effectKey, String providerTrace) {
        if (providerTrace == null || providerTrace.isBlank()) {
            return EffectQueryResult.UNSUPPORTED;
        }

        URI uri;
        try {
            uri = URI.create(providerTrace);
        } catch (Exception invalidUri) {
            log.warn("[effect-query] invalid providerTrace, reject query|runId={} |stepId={} |providerTrace={}",
                    runId, stepId, providerTrace);
            return EffectQueryResult.UNSUPPORTED;
        }

        if (!isHttpScheme(uri)) {
            return EffectQueryResult.UNSUPPORTED;
        }
        if (!isHostAllowed(uri.getHost())) {
            log.warn("[effect-query] providerTrace host is not allowed, reject query|runId={} |stepId={} |host={}",
                    runId, stepId, uri.getHost());
            return EffectQueryResult.UNSUPPORTED;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseResponse(response.statusCode(), response.body());
        } catch (Exception ex) {
            log.warn("[effect-query] remote query exception, conservatively return UNKNOWN|runId={} |stepId={} |error={}",
                    runId, stepId, ex.getMessage());
            return EffectQueryResult.UNKNOWN;
        }
    }

    private EffectQueryResult parseResponse(int statusCode, String body) {
        if (statusCode == 404) {
            return EffectQueryResult.NOT_APPLIED;
        }
        if (statusCode < 200 || statusCode >= 300) {
            return EffectQueryResult.UNKNOWN;
        }

        String normalized = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (normalized.contains("not_applied")
                || normalized.contains("\"status\":\"failed\"")
                || normalized.contains("\"status\":\"not_applied\"")
                || normalized.contains("not applied")) {
            return EffectQueryResult.NOT_APPLIED;
        }
        if (normalized.contains("\"status\":\"applied\"")
                || normalized.contains("\"status\":\"success\"")
                || normalized.contains("\"status\":\"succeeded\"")
                || normalized.contains("applied")
                || normalized.contains("success")) {
            return EffectQueryResult.APPLIED;
        }
        return EffectQueryResult.UNKNOWN;
    }

    private boolean isHttpScheme(URI uri) {
        String scheme = uri == null ? null : uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private boolean isHostAllowed(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return allowedHosts.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> item.toLowerCase(Locale.ROOT))
                .anyMatch(allowed -> allowed.equals(normalized));
    }
}
