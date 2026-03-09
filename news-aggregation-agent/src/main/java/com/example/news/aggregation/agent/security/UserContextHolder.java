package com.example.news.aggregation.agent.security;

/**
 * Request-scoped user context holder.
 */
public final class UserContextHolder {

    private static final ThreadLocal<String> USER_ID_HOLDER = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static void setUserId(String userId) {
        USER_ID_HOLDER.set(userId);
    }

    public static String getUserId() {
        return USER_ID_HOLDER.get();
    }

    public static void clear() {
        USER_ID_HOLDER.remove();
    }
}

