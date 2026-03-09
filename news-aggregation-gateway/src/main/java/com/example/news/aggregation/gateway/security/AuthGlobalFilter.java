package com.example.news.aggregation.gateway.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 网关全局鉴权过滤器。
 * - 统一校验 Authorization
 * - 统一注入可信 X-User-Id
 * - 屏蔽客户端伪造的 X-User-Id
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private final TokenValidator tokenValidator;
    private final GatewayAuthProperties authProperties;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    /**
     * 统一鉴权主流程。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!authProperties.isEnabled()) {
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        if (HttpMethod.OPTIONS.matches(request.getMethod().name()) || isSkipped(path)) {
            return chain.filter(exchange);
        }

        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        TokenValidator.ValidationResult validationResult = tokenValidator.validate(authorization);
        if (!validationResult.valid()) {
            log.warn("[网关鉴权] 未授权请求|method={} |path={} |errorCode={} |errorMessage={}",
                    request.getMethod(), path, validationResult.errorCode(), validationResult.errorMessage());
            return unauthorized(exchange, "UNAUTHORIZED", validationResult.errorMessage());
        }

        Long userId = validationResult.userId();
        String userHeader = authProperties.getUserIdHeader();
        ServerHttpRequest mutatedRequest = request.mutate()
                .headers(headers -> {
                    headers.remove(userHeader);
                    headers.set(userHeader, String.valueOf(userId));
                })
                .build();
        log.debug("[网关鉴权] 鉴权通过并注入用户身份|path={} |userId={}", path, userId);
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    /**
     * 判断是否为免鉴权路径。
     */
    private boolean isSkipped(String path) {
        List<String> skipPaths = authProperties.getSkipPaths();
        if (skipPaths == null || skipPaths.isEmpty()) {
            return false;
        }
        return skipPaths.stream().anyMatch(p -> antPathMatcher.match(p, path));
    }

    /**
     * 返回统一 401 响应。
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = toJson(code, message, exchange.getRequest().getPath().value());
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * 组装错误响应 JSON。
     */
    private String toJson(String code, String message, String path) {
        return "{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"path\":\"" + path + "\"}";
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
