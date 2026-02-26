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
 * Router 服务。
 * 封装 RouterGraph 调用与结果校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouterService {

    private final RouterGraph routerGraph;
    private final OutputValidator validator;
    private final GraphProperties graphProperties;

    /**
     * 路由分析入口。
     *
     * @param request Router 请求
     * @return Router 结果
     */
    public RouterResult route(RouterRequest request) {
        try {
            String sessionId = request != null && request.getSessionId() != null ? request.getSessionId() : "unknown";
            String query = request != null ? request.getQuery() : null;
            // 流程日志：Router 入口
            log.info("进入路由入口FLOW|router|entry|sessionId={}|query={}|next=RouterGraph", sessionId, truncate(query, 200));

            // 配置关闭 RouterGraph 时直接降级
            if (!graphProperties.isRouterEnabled()) {
                log.info("路由降级-Graph关闭FLOW|router|decision=graph_disabled|sessionId={}", sessionId);
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

            log.info("路由结束FLOW|router|exit|sessionId={}|taskFamily={}|retrievalMode={}|needsClarification={}",
                    sessionId, result.getTaskFamily(), result.getRetrievalMode(), result.getNeedsClarification());
            return result;
        } catch (Exception e) {
            log.error("RouterService route failed, fallback to default.", e);
            return RouterResult.defaultQA();
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
