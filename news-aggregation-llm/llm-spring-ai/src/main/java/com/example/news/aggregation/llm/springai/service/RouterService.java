package com.example.news.aggregation.llm.springai.service;

import com.example.news.aggregation.llm.springai.config.GraphProperties;
import com.example.news.aggregation.llm.springai.contract.RouterRequest;
import com.example.news.aggregation.llm.springai.contract.RouterResult;
import com.example.news.aggregation.llm.springai.graph.RouterGraph;
import com.example.news.aggregation.llm.springai.state.RouterState;
import com.example.news.aggregation.llm.springai.validator.OutputValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;

/**
 * Router服务
 * 封装RouterGraph调用与结果校验
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouterService {

    private final RouterGraph routerGraph;
    private final OutputValidator validator;
    private final GraphProperties graphProperties;

    /**
     * 路由分析入口
     *
     * @param request Router请求
     * @return Router结果
     */
    public RouterResult route(RouterRequest request) {
        try {
            // 配置关闭RouterGraph时直接降级
            if (!graphProperties.isRouterEnabled()) {
                return RouterResult.defaultQA();
            }

            RouterState state = RouterState.builder()
                    .sessionId(request.getSessionId())
                    .query(request.getQuery())
                    .history(request.getHistory())
                    .constraints(request.getConstraints())
                    .params(request.getConstraints() != null ? new HashMap<>(request.getConstraints()) : new HashMap<>())
                    .build();

            RouterState finalState = routerGraph.invoke(state);
            RouterResult result = finalState.toRouterResult();

            if (!validator.validateRouter(result)) {
                log.warn("Router result validation failed, fallback to default.");
                return RouterResult.defaultQA();
            }

            return result;
        } catch (Exception e) {
            log.error("RouterService route failed, fallback to default.", e);
            return RouterResult.defaultQA();
        }
    }
}
