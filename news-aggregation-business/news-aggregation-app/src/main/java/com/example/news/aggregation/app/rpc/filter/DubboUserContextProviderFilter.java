package com.example.news.aggregation.app.rpc.filter;

import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.slf4j.MDC;

/**
 * Reads propagated user identity from Dubbo attachments.
 */
public class DubboUserContextProviderFilter implements Filter {

    private static final String USER_ID_ATTACHMENT_KEY = "x-user-id";
    private static final String USER_ID_MDC_KEY = "userId";

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        String userId = RpcContext.getServerAttachment().getAttachment(USER_ID_ATTACHMENT_KEY);
        if (userId != null && !userId.isBlank()) {
            MDC.put(USER_ID_MDC_KEY, userId);
        }
        try {
            return invoker.invoke(invocation);
        } finally {
            MDC.remove(USER_ID_MDC_KEY);
        }
    }
}

