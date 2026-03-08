package com.example.news.aggregation.agent.enums;

/**
 * 消息角色枚举。
 */
public enum MessageRole {
    USER(0, "用户"),
    ASSISTANT(1, "系统");

    private final int code;
    private final String description;

    MessageRole(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static MessageRole fromCode(int code) {
        for (MessageRole role : values()) {
            if (role.code == code) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown message role code: " + code);
    }
}
