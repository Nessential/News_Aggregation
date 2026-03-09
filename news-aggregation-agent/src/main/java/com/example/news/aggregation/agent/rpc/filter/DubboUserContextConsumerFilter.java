package com.example.news.aggregation.agent.rpc.filter;

import com.example.news.aggregation.agent.security.UserContextHolder;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;

/**
 * Propagates user identity from HTTP context to Dubbo attachments.
 */
public class DubboUserContextConsumerFilter implements Filter {

    public static final String USER_ID_ATTACHMENT_KEY = "x-user-id";

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        String userId = UserContextHolder.getUserId();
        if (userId != null && !userId.isBlank()) {
            RpcContext.getClientAttachment().setAttachment(USER_ID_ATTACHMENT_KEY, userId);
        }
        return invoker.invoke(invocation);
    }
}

