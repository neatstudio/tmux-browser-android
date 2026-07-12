package com.neatstudio.tmuxandroid;

import org.json.JSONObject;

final class ConversationMessage {
    final String messageId;
    final String sessionName;
    final String role;
    final String contentType;
    final String content;
    final String status;
    final String toolName;
    final String parentMessageId;
    final String createdAt;
    final boolean local;

    ConversationMessage(
            String messageId,
            String sessionName,
            String role,
            String contentType,
            String content,
            String status,
            String toolName,
            String parentMessageId,
            String createdAt,
            boolean local
    ) {
        this.messageId = messageId;
        this.sessionName = sessionName;
        this.role = role;
        this.contentType = contentType;
        this.content = content;
        this.status = status;
        this.toolName = toolName;
        this.parentMessageId = parentMessageId;
        this.createdAt = createdAt;
        this.local = local;
    }

    static ConversationMessage fromJson(JSONObject object) {
        return new ConversationMessage(
                value(object, "messageId", object.optString("id", "")),
                object.optString("sessionName", ""),
                object.optString("role", "assistant"),
                object.optString("contentType", "text"),
                object.optString("content", ""),
                object.optString("status", "complete"),
                object.optString("toolName", ""),
                value(object, "parentMessageId", ""),
                object.optString("createdAt", ""),
                false
        );
    }

    static ConversationMessage localUser(String sessionName, String content) {
        long now = System.currentTimeMillis();
        return new ConversationMessage(
                "local-" + now,
                sessionName,
                "user",
                "text",
                content,
                "sending",
                "",
                "",
                "~" + now,
                true
        );
    }

    boolean isTool() {
        return "tool".equals(role)
                || "tool".equals(contentType)
                || "command".equals(contentType)
                || "code".equals(contentType);
    }

    private static String value(JSONObject object, String key, String fallback) {
        if (!object.has(key) || object.isNull(key)) {
            return fallback;
        }
        return object.optString(key, fallback);
    }
}
