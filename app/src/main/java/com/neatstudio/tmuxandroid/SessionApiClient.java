package com.neatstudio.tmuxandroid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class SessionApiClient {
    private final String baseUrl;

    SessionApiClient(String baseUrl) {
        this.baseUrl = trimTrailingSlash(baseUrl);
    }

    List<SessionSummary> getSessions() throws Exception {
        String text = request("GET", "/api/sessions", null);
        JSONArray array = new JSONArray(text);
        List<SessionSummary> sessions = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            sessions.add(new SessionSummary(
                    item.getString("name"),
                    item.optString("status", ""),
                    nullableString(item, "currentCommand"),
                    nullableString(item, "currentPath"),
                    item.optInt("windows", 0),
                    item.optInt("paneCount", 0)
            ));
        }
        return sessions;
    }

    void createSession(String name) throws Exception {
        JSONObject body = new JSONObject().put("name", name);
        request("POST", "/api/sessions", body.toString());
    }

    void killSession(String name) throws Exception {
        request("DELETE", "/api/sessions/" + encodePath(name), null);
    }

    void renameSession(String fromName, String toName) throws Exception {
        JSONObject body = new JSONObject().put("name", toName);
        request("PATCH", "/api/sessions/" + encodePath(fromName), body.toString());
    }

    void sendCommand(String sessionName, String command) throws Exception {
        JSONObject body = new JSONObject().put("command", command);
        request("POST", "/api/sessions/" + encodePath(sessionName) + "/send", body.toString());
    }

    void sendInput(String sessionName, String input) throws Exception {
        JSONObject body = new JSONObject().put("input", input);
        request("POST", "/api/sessions/" + encodePath(sessionName) + "/input", body.toString());
    }

    void splitPane(String sessionName, String direction) throws Exception {
        JSONObject body = new JSONObject().put("direction", direction);
        request("POST", "/api/sessions/" + encodePath(sessionName) + "/split", body.toString());
    }

    void selectPane(String sessionName, String paneId) throws Exception {
        JSONObject body = new JSONObject().put("paneId", paneId);
        request("POST", "/api/sessions/" + encodePath(sessionName) + "/select-pane", body.toString());
    }

    void killPane(String sessionName, String paneId) throws Exception {
        request("DELETE", "/api/sessions/" + encodePath(sessionName) + "/panes/" + encodePath(paneId), null);
    }

    void setPinned(String sessionName, boolean pinned) throws Exception {
        JSONObject body = new JSONObject().put("pinned", pinned);
        request("PATCH", "/api/preferences/pinned-sessions/" + encodePath(sessionName), body.toString());
    }

    void setMuted(String sessionName, boolean muted) throws Exception {
        JSONObject body = new JSONObject().put("muted", muted);
        request("PATCH", "/api/preferences/muted-sessions/" + encodePath(sessionName), body.toString());
    }

    void updateSessionSettings(String sessionName, int fontSize, String fontFamily, double lineHeight, String themeId) throws Exception {
        JSONObject settings = new JSONObject()
                .put("fontSize", fontSize)
                .put("fontFamily", fontFamily)
                .put("lineHeight", lineHeight)
                .put("themeId", themeId);
        JSONObject body = new JSONObject().put("settings", settings);
        request("PATCH", "/api/preferences/session-settings/" + encodePath(sessionName), body.toString());
    }

    void createKanbanProject(String name, String path, String server) throws Exception {
        JSONObject body = new JSONObject()
                .put("name", name)
                .put("path", path)
                .put("server", server == null || server.isEmpty() ? JSONObject.NULL : server);
        request("POST", "/api/kanban/projects", body.toString());
    }

    void deleteKanbanProject(String name) throws Exception {
        request("DELETE", "/api/kanban/projects/" + encodePath(name), null);
    }

    void addKanbanSession(String projectName, String sessionName) throws Exception {
        JSONObject body = new JSONObject().put("sessionName", sessionName);
        request("POST", "/api/kanban/projects/" + encodePath(projectName) + "/sessions", body.toString());
    }

    void removeKanbanSession(String projectName, String agentName, boolean kill) throws Exception {
        request(
                "DELETE",
                "/api/kanban/projects/" + encodePath(projectName) + "/sessions/" + encodePath(agentName) + "?kill=" + kill,
                null
        );
    }

    void sendGroupMessage(String projectName, String fromSession, String kind, String targetType, String targetValue, String bodyText) throws Exception {
        JSONObject target = new JSONObject().put("type", targetType);
        if ("session".equals(targetType)) {
            target.put("sessionName", targetValue);
        } else if ("role".equals(targetType)) {
            target.put("role", targetValue);
        }
        JSONObject body = new JSONObject()
                .put("fromSession", fromSession)
                .put("kind", kind)
                .put("target", target)
                .put("body", bodyText);
        request("POST", "/api/kanban/projects/" + encodePath(projectName) + "/messages", body.toString());
    }

    void scanGroupMessage(String projectName, String messageId) throws Exception {
        request("POST", "/api/kanban/projects/" + encodePath(projectName) + "/messages/" + encodePath(messageId) + "/scan", "{}");
    }

    void postHookEvent(String sessionName, String title, String status, String bodyText) throws Exception {
        JSONObject body = new JSONObject()
                .put("source", "android")
                .put("sessionName", sessionName)
                .put("eventType", "mobile-event")
                .put("status", status)
                .put("title", title)
                .put("body", bodyText);
        request("POST", "/api/hooks/events", body.toString());
    }

    String uploadImageUrl(String sessionName, String imageUrl) throws Exception {
        JSONObject body = new JSONObject().put("url", imageUrl);
        return request("POST", "/api/uploads/image-url", body.toString(), sessionName);
    }

    String uploadImage(String sessionName, byte[] bytes) throws Exception {
        return prettyJson(requestBytes("POST", "/api/uploads/image", bytes, sessionName));
    }

    byte[] imagePreview(String path, String basePath) throws Exception {
        String query = "?path=" + encodePath(path);
        if (basePath != null && !basePath.isEmpty()) {
            query += "&basePath=" + encodePath(basePath);
        }
        return requestBinary("GET", "/api/image-preview" + query, null, null);
    }

    String imagePreviewInfo(String path, String basePath) throws Exception {
        String query = "?path=" + encodePath(path);
        if (basePath != null && !basePath.isEmpty()) {
            query += "&basePath=" + encodePath(basePath);
        }
        return prettyJson(request("GET", "/api/image-preview-info" + query, null));
    }

    String health() throws Exception {
        return prettyJson(request("GET", "/api/health", null));
    }

    String serverStatus() throws Exception {
        return prettyJson(request("GET", "/api/server-status", null));
    }

    String timeline(int limit) throws Exception {
        return prettyJson(request("GET", "/api/timeline?limit=" + limit, null));
    }

    String preferences() throws Exception {
        return prettyJson(request("GET", "/api/preferences", null));
    }

    String sessionsAll() throws Exception {
        return prettyJson(request("GET", "/api/sessions-all", null));
    }

    String sessionsPanes() throws Exception {
        return prettyJson(request("GET", "/api/sessions-panes", null));
    }

    String sessionStatus(String sessionName) throws Exception {
        return prettyJson(request("GET", "/api/sessions/" + encodePath(sessionName) + "/status", null));
    }

    String kanbanProjects() throws Exception {
        return prettyJson(request("GET", "/api/kanban/projects", null));
    }

    String groupMessages(String projectName) throws Exception {
        return prettyJson(request("GET", "/api/kanban/projects/" + encodePath(projectName) + "/messages", null));
    }

    String getBaseUrl() {
        return baseUrl;
    }

    private String request(String method, String path, String body) throws Exception {
        return request(method, path, body, null);
    }

    private String request(String method, String path, String body, String sessionNameHeader) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/json");
        if (sessionNameHeader != null && !sessionNameHeader.isEmpty()) {
            connection.setRequestProperty("X-Tmux-Session", sessionNameHeader);
        }
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }

        int code = connection.getResponseCode();
        InputStream rawInput = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String text = rawInput == null ? "" : new String(readAllBytes(new BufferedInputStream(rawInput)), StandardCharsets.UTF_8);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            String message = text;
            try {
                message = new JSONObject(text).optString("error", text);
            } catch (Exception ignored) {
            }
            throw new IllegalStateException(method + " " + path + " failed: " + message);
        }
        return text;
    }

    private String requestBytes(String method, String path, byte[] body, String sessionNameHeader) throws Exception {
        return new String(requestBinary(method, path, body, sessionNameHeader), StandardCharsets.UTF_8);
    }

    private byte[] requestBinary(String method, String path, byte[] body, String sessionNameHeader) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(30000);
        if (sessionNameHeader != null && !sessionNameHeader.isEmpty()) {
            connection.setRequestProperty("X-Tmux-Session", sessionNameHeader);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setRequestProperty("Content-Length", String.valueOf(body.length));
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
        }
        int code = connection.getResponseCode();
        InputStream rawInput = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        byte[] bytes = rawInput == null ? new byte[0] : readAllBytes(new BufferedInputStream(rawInput));
        connection.disconnect();
        if (code < 200 || code >= 300) {
            String message = new String(bytes, StandardCharsets.UTF_8);
            try {
                message = new JSONObject(message).optString("error", message);
            } catch (Exception ignored) {
            }
            throw new IllegalStateException(method + " " + path + " failed: " + message);
        }
        return bytes;
    }

    private static String prettyJson(String text) {
        try {
            String trimmed = text.trim();
            if (trimmed.startsWith("[")) {
                return new JSONArray(trimmed).toString(2);
            }
            if (trimmed.startsWith("{")) {
                return new JSONObject(trimmed).toString(2);
            }
        } catch (Exception ignored) {
        }
        return text;
    }

    private static byte[] readAllBytes(BufferedInputStream input) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String nullableString(JSONObject item, String key) {
        return item.isNull(key) ? null : item.optString(key, null);
    }

    private static String encodePath(String value) throws Exception {
        return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/") && result.length() > "http://x".length()) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
