package com.example.news.aggregation.agent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import org.springframework.http.HttpMethod;

/**
 * Enforces authenticated user identity from gateway headers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class UserContextFilter extends OncePerRequestFilter {

    @Value("${app.auth.enforce:true}")
    private boolean authEnforce;

    @Value("${app.auth.user-id-header:X-User-Id}")
    private String userIdHeader;

    @Value("${app.auth.protected-path-prefix:/api/agent}")
    private String protectedPathPrefix;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!authEnforce) {
            return true;
        }
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return path == null || !path.startsWith(protectedPathPrefix);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String userId = request.getHeader(userIdHeader);
        if (userId == null || userId.isBlank()) {
            Object userIdAttr = request.getAttribute(userIdHeader);
            if (userIdAttr != null) {
                userId = String.valueOf(userIdAttr);
            }
        }
        if (userId == null || userId.isBlank()) {
            log.warn("[auth] unauthorized request blocked|method={} |path={} |remoteIp={} |header={} missing",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr(), userIdHeader);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Authentication required\"}");
            return;
        }

        String trimmedUserId = userId.trim();
        UserContextHolder.setUserId(trimmedUserId);
        log.debug("[auth] user context bound|method={} |path={} |userId={}",
                request.getMethod(), request.getRequestURI(), trimmedUserId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContextHolder.clear();
        }
    }
}
