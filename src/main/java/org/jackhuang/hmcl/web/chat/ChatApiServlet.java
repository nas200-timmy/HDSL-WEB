/*
 * HDSL-web
 * Copyright (C) 2026  HDSL-web contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.web.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.jackhuang.hmcl.web.auth.AuthFilter;
import org.jackhuang.hmcl.web.auth.AuthService;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.developer.DeveloperElevation;
import org.jackhuang.hmcl.web.http.Json;
import org.jackhuang.hmcl.web.http.RequestSecurity;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/// `/api/chat/*`（docs/chat.md §10）。
///
/// 会话、消息与附件走 REST；token 流走 `/ws` 的 `chat:<id>` topic，这里不发流。
/// 所有 `/api/chat/*` 都对 agent ticket 关闭（见 [org.jackhuang.hmcl.web.developer.AgentScopes]）；
/// 设置、备份、端点探测与能力覆盖另外要求开发者提权。
@NotNullByDefault
public final class ChatApiServlet extends HttpServlet {

    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;

    private final ChatService chat;
    private final ServerConfig config;
    private final AuthService auth;
    private final DeveloperElevation elevation;
    private final BooleanSupplier tlsActive;

    public ChatApiServlet(ChatService chat, ServerConfig config, AuthService auth,
                          DeveloperElevation elevation, BooleanSupplier tlsActive) {
        this.chat = chat;
        this.config = config;
        this.auth = auth;
        this.elevation = elevation;
        this.tlsActive = tlsActive;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo() == null ? "/" : request.getPathInfo();
        String method = request.getMethod();
        try {
            route(request, response, path, method);
        } catch (ChatException e) {
            JsonObject body = new JsonObject();
            body.addProperty("error", e.getMessage());
            body.addProperty("code", e.code());
            Json.write(response, e.status(), body);
        } catch (SQLException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Chat store failed", e);
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "chat store failed: " + e.getMessage());
        } catch (IOException e) {
            // A client that left mid-download is not an API failure worth a 500 body.
            if (!response.isCommitted()) {
                Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            }
        } catch (RuntimeException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Chat request failed", e);
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void route(HttpServletRequest request, HttpServletResponse response,
                       String path, String method) throws IOException, SQLException, ChatException {
        String route = path.length() > 1 && path.endsWith("/")
                ? path.substring(0, path.length() - 1) : path;

        if ("/sessions".equals(route)) {
            if ("GET".equals(method)) {
                String assistant = request.getParameter("assistant");
                JsonObject body = new JsonObject();
                body.add("sessions", chat.sessions(assistant == null || assistant.isBlank()
                        ? null : assistant));
                Json.write(response, body);
                return;
            }
            if ("POST".equals(method)) {
                JsonObject body = readBody(request, response);
                if (body != null) {
                    Json.write(response, HttpServletResponse.SC_CREATED, chat.createSession(body));
                }
                return;
            }
            method(response, "GET, POST");
            return;
        }

        if (route.startsWith("/sessions/")) {
            String rest = route.substring("/sessions/".length());
            int slash = rest.indexOf('/');
            String sessionId = slash < 0 ? rest : rest.substring(0, slash);
            String tail = slash < 0 ? "" : rest.substring(slash);
            if (tail.isEmpty()) {
                if ("GET".equals(method)) {
                    Json.write(response, chat.sessionJson(sessionId));
                    return;
                }
                if ("PATCH".equals(method)) {
                    JsonObject body = readBody(request, response);
                    if (body != null) {
                        Json.write(response, chat.updateSession(sessionId, body));
                    }
                    return;
                }
                if ("DELETE".equals(method)) {
                    chat.deleteSession(sessionId);
                    Json.write(response, java.util.Map.of("ok", true));
                    return;
                }
                method(response, "GET, PATCH, DELETE");
                return;
            }
            if ("/messages".equals(tail) && "POST".equals(method)) {
                JsonObject body = readBody(request, response);
                if (body == null) {
                    return;
                }
                String text = string(body, "text", "");
                List<String> attachments = strings(body, "attachments");
                boolean search = body.has("search") && body.get("search").isJsonPrimitive()
                        && body.get("search").getAsBoolean();
                ChatTurnManager.Outcome outcome = chat.turns().send(sessionId, text, attachments,
                        nullable(body, "model"), nullable(body, "account"),
                        nullable(body, "requestId"), search);
                Json.write(response, outcomeJson(outcome));
                return;
            }
            if ("/regenerate".equals(tail) && "POST".equals(method)) {
                JsonObject body = readBody(request, response);
                if (body == null) {
                    return;
                }
                String nodeId = required(body, "nodeId");
                String mode = string(body, "mode", "truncate_after");
                ChatTurnManager.Outcome outcome = chat.turns().regenerate(sessionId, nodeId, mode,
                        nullable(body, "requestId"));
                Json.write(response, outcomeJson(outcome));
                return;
            }
            if ("/edit".equals(tail) && "POST".equals(method)) {
                JsonObject body = readBody(request, response);
                if (body == null) {
                    return;
                }
                String nodeId = required(body, "nodeId");
                String text = string(body, "text", "");
                ChatTurnManager.Outcome outcome = chat.turns().edit(sessionId, nodeId, text,
                        nullable(body, "requestId"));
                Json.write(response, outcomeJson(outcome));
                return;
            }
            if (tail.startsWith("/nodes/")) {
                String nodeRest = tail.substring("/nodes/".length());
                int nodeSlash = nodeRest.indexOf('/');
                String nodeId = nodeSlash < 0 ? nodeRest : nodeRest.substring(0, nodeSlash);
                String action = nodeSlash < 0 ? "" : nodeRest.substring(nodeSlash);
                if (action.isEmpty() && "DELETE".equals(method)) {
                    Json.write(response, chat.deleteNode(sessionId, nodeId));
                    return;
                }
                if ("/variant".equals(action) && "POST".equals(method)) {
                    JsonObject body = readBody(request, response);
                    if (body != null) {
                        Json.write(response, chat.switchVariant(sessionId, nodeId,
                                required(body, "messageId")));
                    }
                    return;
                }
            }
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
            return;
        }

        if (route.startsWith("/turns/") && route.endsWith("/cancel") && "POST".equals(method)) {
            String rest = route.substring("/turns/".length(), route.length() - "/cancel".length());
            if (rest.isEmpty() || rest.contains("/")) {
                Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
                return;
            }
            Json.write(response, java.util.Map.of("ok", chat.turns().cancel(rest)));
            return;
        }

        if ("/attachments".equals(route)) {
            if (!"POST".equals(method)) {
                method(response, "POST");
                return;
            }
            String sessionId = request.getParameter("sessionId");
            if (sessionId == null || sessionId.isBlank()) {
                throw new ChatException(400, "session_required", "sessionId is required");
            }
            Part part;
            try {
                part = request.getPart("file");
            } catch (jakarta.servlet.ServletException e) {
                throw new ChatException(400, "multipart_error", e.getMessage());
            }
            if (part == null || part.getSize() == 0) {
                throw new ChatException(400, "file_required", "a file part is required");
            }
            Json.write(response, HttpServletResponse.SC_CREATED,
                    chat.uploadAttachment(sessionId, part));
            return;
        }

        if (route.startsWith("/attachments/") && "GET".equals(method)) {
            download(response, route.substring("/attachments/".length()));
            return;
        }

        if ("/search/status".equals(route) && "GET".equals(method)) {
            Json.write(response, chat.searchStatus());
            return;
        }

        if ("/search/test".equals(route) && "POST".equals(method)) {
            if (!requireElevation(request, response)) {
                return;
            }
            JsonObject body = readBody(request, response);
            if (body != null) {
                Json.write(response, chat.searchTest(string(body, "query", "")));
            }
            return;
        }

        if ("/profile".equals(route)) {
            if ("GET".equals(method)) {
                Json.write(response, chat.profile(usernameOf(request)));
                return;
            }
            if ("PUT".equals(method) || "PATCH".equals(method)) {
                JsonObject body = readBody(request, response);
                if (body != null) {
                    Json.write(response, chat.updateProfile(body, usernameOf(request)));
                }
                return;
            }
            method(response, "GET, PUT");
            return;
        }

        if ("/avatars".equals(route)) {
            if (!"POST".equals(method)) {
                method(response, "POST");
                return;
            }
            Part part;
            try {
                part = request.getPart("file");
            } catch (jakarta.servlet.ServletException e) {
                throw new ChatException(400, "multipart_error", e.getMessage());
            }
            String id = chat.uploadAvatar(part);
            JsonObject body = new JsonObject();
            body.addProperty("id", id);
            body.addProperty("url", "/api/chat/avatars/" + id);
            Json.write(response, HttpServletResponse.SC_CREATED, body);
            return;
        }

        if (route.startsWith("/avatars/") && "GET".equals(method)) {
            serveAvatar(response, route.substring("/avatars/".length()));
            return;
        }

        if ("/settings".equals(route)) {
            if ("GET".equals(method)) {
                if (!requireElevation(request, response)) {
                    return;
                }
                Json.write(response, chat.settingsJson());
                return;
            }
            if ("PATCH".equals(method) || "PUT".equals(method)) {
                if (!requireElevation(request, response)) {
                    return;
                }
                JsonObject body = readBody(request, response);
                if (body != null) {
                    Json.write(response, chat.updateSettings(body));
                }
                return;
            }
            method(response, "GET, PATCH");
            return;
        }

        if ("/backups".equals(route) && "GET".equals(method)) {
            if (!requireElevation(request, response)) {
                return;
            }
            JsonObject body = new JsonObject();
            body.add("backups", chat.backups());
            Json.write(response, body);
            return;
        }
        if ("/backup".equals(route) && "POST".equals(method)) {
            if (!requireElevation(request, response)) {
                return;
            }
            Json.write(response, chat.backupNow());
            return;
        }

        if ("/models".equals(route) && "GET".equals(method)) {
            String account = request.getParameter("account");
            if (account == null || account.isBlank()) {
                throw new ChatException(400, "account_required", "account is required");
            }
            Json.write(response, chat.models(account));
            return;
        }

        if ("/accounts".equals(route) && "GET".equals(method)) {
            JsonObject body = new JsonObject();
            body.add("accounts", chat.accounts());
            Json.write(response, body);
            return;
        }

        if (route.startsWith("/accounts/")) {
            String rest = route.substring("/accounts/".length());
            int slash = rest.indexOf('/');
            String account = slash < 0 ? rest : rest.substring(0, slash);
            String action = slash < 0 ? "" : rest.substring(slash);
            if (action.isEmpty() && ("PATCH".equals(method) || "PUT".equals(method))) {
                if (!requireElevation(request, response)) {
                    return;
                }
                JsonObject body = readBody(request, response);
                if (body != null) {
                    Json.write(response, chat.saveAccountProfile(account, body));
                }
                return;
            }
            if ("/probe".equals(action) && "POST".equals(method)) {
                if (!requireElevation(request, response)) {
                    return;
                }
                Json.write(response, chat.probe(account));
                return;
            }
            if ("/chat-test".equals(action) && "POST".equals(method)) {
                if (!requireElevation(request, response)) {
                    return;
                }
                JsonObject body = readBody(request, response);
                if (body != null) {
                    Json.write(response, chat.chatTest(account, nullable(body, "model")));
                }
                return;
            }
            if ("/vision-test".equals(action) && "POST".equals(method)) {
                if (!requireElevation(request, response)) {
                    return;
                }
                JsonObject body = readBody(request, response);
                String model = body == null ? null : nullable(body, "model");
                if (model == null || model.isBlank()) {
                    Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "model is required");
                    return;
                }
                Json.write(response, chat.visionTest(account, model));
                return;
            }
            if ("/models/refresh".equals(action) && "POST".equals(method)) {
                if (!requireElevation(request, response)) {
                    return;
                }
                JsonObject body = new JsonObject();
                JsonArray models = new JsonArray();
                chat.refreshModels(account).forEach(models::add);
                body.add("models", models);
                Json.write(response, body);
                return;
            }
            if ("/capabilities".equals(action) && "POST".equals(method)) {
                if (!requireElevation(request, response)) {
                    return;
                }
                JsonObject body = readBody(request, response);
                if (body != null) {
                    Json.write(response, chat.saveCapability(account, body));
                }
                return;
            }
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
            return;
        }

        if ("/assistants".equals(route)) {
            if ("GET".equals(method)) {
                JsonObject body = new JsonObject();
                body.add("assistants", chat.assistants());
                Json.write(response, body);
                return;
            }
            if ("POST".equals(method)) {
                JsonObject body = readBody(request, response);
                if (body != null) {
                    Json.write(response, HttpServletResponse.SC_CREATED,
                            chat.assistantJson(chat.createAssistant(body)));
                }
                return;
            }
            method(response, "GET, POST");
            return;
        }

        if (route.startsWith("/assistants/")) {
            String rest = route.substring("/assistants/".length());
            int slash = rest.indexOf('/');
            String id = slash < 0 ? rest : rest.substring(0, slash);
            String action = slash < 0 ? "" : rest.substring(slash);
            if (action.isEmpty()) {
                if ("PATCH".equals(method) || "PUT".equals(method)) {
                    JsonObject body = readBody(request, response);
                    if (body != null) {
                        Json.write(response, chat.assistantJson(chat.updateAssistant(id, body)));
                    }
                    return;
                }
                if ("DELETE".equals(method)) {
                    chat.deleteAssistant(id);
                    Json.write(response, java.util.Map.of("ok", true));
                    return;
                }
                method(response, "PATCH, DELETE");
                return;
            }
            if ("/duplicate".equals(action) && "POST".equals(method)) {
                Json.write(response, HttpServletResponse.SC_CREATED,
                        chat.assistantJson(chat.duplicateAssistant(id)));
                return;
            }
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
            return;
        }

        Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
    }

    /// 头像文件：服务端重编码过的 PNG，文件名是内容哈希，可以长缓存。
    private void serveAvatar(HttpServletResponse response, String id) throws IOException {
        Path path = chat.avatarFile(id);
        if (path == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "avatar not found");
            return;
        }
        response.setStatus(200);
        response.setContentType("image/png");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "private, max-age=86400, immutable");
        response.setHeader("Content-Security-Policy", "default-src 'none'");
        try (InputStream input = Files.newInputStream(path)) {
            input.transferTo(response.getOutputStream());
        }
    }

    /// 当前登录用户；auth 关闭时面板只有一个 admin，和 `/api/auth/whoami` 保持一致。
    private static String usernameOf(HttpServletRequest request) {
        Object session = request.getAttribute(AuthFilter.SESSION_ATTRIBUTE);
        return session instanceof AuthService.Session value ? value.username() : "admin";
    }

    private void download(HttpServletResponse response, String id) throws IOException, SQLException {
        ChatStore.Attachment attachment = chat.attachment(id);
        if (attachment == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "attachment not found");
            return;
        }
        Path path = Path.of(attachment.path());
        if (!Files.isRegularFile(path)) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "attachment file is missing");
            return;
        }
        response.setStatus(200);
        response.setContentType(attachment.mime());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "private, max-age=3600");
        String encoded = URLEncoder.encode(attachment.name(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setHeader("Content-Disposition", "inline; filename*=UTF-8''" + encoded);
        try (InputStream input = Files.newInputStream(path)) {
            input.transferTo(response.getOutputStream());
        }
    }

    private static JsonObject outcomeJson(ChatTurnManager.Outcome outcome) {
        JsonObject json = new JsonObject();
        json.addProperty("turnId", outcome.turnId());
        if (outcome.userMessage() != null) {
            json.add("userMessage", messageJson(outcome.userMessage()));
        }
        if (outcome.assistantMessage() != null) {
            json.add("assistantMessage", messageJson(outcome.assistantMessage()));
        }
        if (outcome.assistantNode() != null) {
            json.addProperty("assistantNodeId", outcome.assistantNode().id());
        }
        return json;
    }

    private static JsonObject messageJson(ChatStore.Message message) {
        JsonObject json = new JsonObject();
        json.addProperty("id", message.id());
        json.addProperty("nodeId", message.nodeId());
        json.addProperty("variantIndex", message.variantIndex());
        json.addProperty("content", message.content());
        json.addProperty("status", message.status());
        json.addProperty("createdAt", message.createdAt());
        return json;
    }

    /// 设置、备份、端点探测与能力覆盖要求开发者提权。
    private boolean requireElevation(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!RequestSecurity.isSecure(request, tlsActive.getAsBoolean(), config)) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "tls_required: the developer password is only accepted over HTTPS");
            return false;
        }
        if (!AuthFilter.elevationSatisfied(request, elevation, keyOf(request), sessionAlive(request))) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN,
                    "developer_elevation_required: elevate with the developer password first");
            return false;
        }
        return true;
    }

    private static String keyOf(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return DeveloperElevation.key(token instanceof String value ? value : null,
                request.getRemoteAddr());
    }

    private boolean sessionAlive(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return !(token instanceof String value) || value.isBlank() || auth.isAlive(value);
    }

    private static void method(HttpServletResponse response, String allowed) throws IOException {
        response.setHeader("Allow", allowed);
        Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method not allowed");
    }

    private static @Nullable JsonObject readBody(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(MAX_JSON_BYTES + 1);
        if (bytes.length > MAX_JSON_BYTES) {
            Json.error(response, 413, "body too large");
            return null;
        }
        if (bytes.length == 0) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "a JSON body is required");
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "a JSON object is required");
                return null;
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "malformed JSON");
            return null;
        }
    }

    private static String required(JsonObject body, String name) throws ChatException {
        String value = nullable(body, name);
        if (value == null || value.isBlank()) {
            throw new ChatException(400, "invalid_request", name + " is required");
        }
        return value;
    }

    private static String string(JsonObject body, String name, String fallback) {
        String value = nullable(body, name);
        return value == null ? fallback : value;
    }

    private static @Nullable String nullable(JsonObject body, String name) {
        JsonElement element = body.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    private static List<String> strings(JsonObject body, String name) {
        List<String> result = new ArrayList<>();
        JsonElement element = body.get(name);
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonPrimitive()) {
                    result.add(item.getAsString());
                }
            }
        }
        return result;
    }
}