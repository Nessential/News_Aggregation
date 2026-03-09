package com.example.news.aggregation.web.filter;

import com.example.news.aggregation.web.util.TokenUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.BooleanUtils;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

/**
 * @author Hollis
 */
public class TokenFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(TokenFilter.class);

    public static final ThreadLocal<String> TOKEN_THREAD_LOCAL = new ThreadLocal<>();

    public static final ThreadLocal<Boolean> STRESS_THREAD_LOCAL = new ThreadLocal<>();

    private static final String HEADER_VALUE_NULL = "null";

    private static final String HEADER_VALUE_UNDEFINED = "undefined";

    private static final String ATTR_USER_ID = "X-User-Id";

    private final RedissonClient redissonClient;

    public TokenFilter(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // no-op
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        try {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            HttpServletResponse httpResponse = (HttpServletResponse) response;

            String token = httpRequest.getHeader("Authorization");
            Boolean isStress = BooleanUtils.toBoolean(httpRequest.getHeader("isStress"));

            if (token == null || HEADER_VALUE_NULL.equals(token) || HEADER_VALUE_UNDEFINED.equals(token)) {
                httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                httpResponse.getWriter().write("No Token Found ...");
                logger.error("no token found in header, please check");
                return;
            }

            TokenValidationResult validationResult = checkTokenValidity(token, isStress);
            if (!validationResult.valid()) {
                httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                httpResponse.getWriter().write("Invalid or expired token");
                logger.error("token validate failed, please check");
                return;
            }

            if (validationResult.userId() != null) {
                httpRequest.setAttribute(ATTR_USER_ID, String.valueOf(validationResult.userId()));
            }

            chain.doFilter(request, response);
        } finally {
            TOKEN_THREAD_LOCAL.remove();
            STRESS_THREAD_LOCAL.remove();
        }
    }

    private TokenValidationResult checkTokenValidity(String token, Boolean isStress) {
        String result;
        if (Boolean.TRUE.equals(isStress)) {
            result = UUID.randomUUID().toString();
            STRESS_THREAD_LOCAL.set(isStress);
            TOKEN_THREAD_LOCAL.set(result);
            return TokenValidationResult.ok(null);
        }

        TokenUtil.TokenPayload tokenPayload;
        try {
            tokenPayload = TokenUtil.parseToken(token);
        } catch (Exception ex) {
            logger.warn("parse token failed", ex);
            return TokenValidationResult.failed();
        }
        if (tokenPayload == null || tokenPayload.tokenKey() == null || tokenPayload.uuid() == null) {
            return TokenValidationResult.failed();
        }

        String luaScript = """
            local value = redis.call('GET', KEYS[1])
            if value ~= ARGV[1] then
                return redis.error_reply('token not valid')
            end
            local expireSeconds = tonumber(ARGV[2]) or 86400
            redis.call('EXPIRE', KEYS[1], expireSeconds)
            return value
            """;

        try {
            result = (String) redissonClient.getScript().eval(
                    RScript.Mode.READ_WRITE,
                    luaScript,
                    RScript.ReturnType.STATUS,
                    Arrays.asList(tokenPayload.tokenKey()),
                    tokenPayload.uuid(),
                    "86400"
            );
        } catch (RedisException e) {
            logger.error("check token failed", e);
            return TokenValidationResult.failed();
        }

        TOKEN_THREAD_LOCAL.set(result);
        if (result == null) {
            return TokenValidationResult.failed();
        }
        return TokenValidationResult.ok(tokenPayload.userId());
    }

    @Override
    public void destroy() {
    }

    private record TokenValidationResult(boolean valid, Long userId) {
        private static TokenValidationResult ok(Long userId) {
            return new TokenValidationResult(true, userId);
        }

        private static TokenValidationResult failed() {
            return new TokenValidationResult(false, null);
        }
    }
}
