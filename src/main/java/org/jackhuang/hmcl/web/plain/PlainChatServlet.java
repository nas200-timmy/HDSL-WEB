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
package org.jackhuang.hmcl.web.plain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jackhuang.hmcl.web.auth.AuthFilter;
import org.jackhuang.hmcl.web.auth.AuthService;
import org.jackhuang.hmcl.web.auth.LoginThrottle;
import org.jackhuang.hmcl.web.audit.AuditLog;
import org.jackhuang.hmcl.web.chat.ChatException;
import org.jackhuang.hmcl.web.chat.ChatService;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The no-JavaScript chat surface, for text browsers and old machines.
///
/// One HTML page per action, forms and links only: log in, list sessions, read a
/// transcript, send a message, download an attachment. A reply arrives through
/// the same [ChatService] turn the SPA uses; the page offers a manual refresh and,
/// while a turn runs, an optional `<meta http-equiv=refresh>` so "submit, then
/// look at the result" works without a script.
///
/// Deliberately not behind [AuthFilter]: the filter answers an unauthenticated
/// HTML request with a JSON 401, and a text browser would show that body. Every
/// route here checks the session itself and renders the login form instead.
@NotNullByDefault
public final class PlainChatServlet extends HttpServlet {

    /// The friendly page a browser that cannot run the SPA is sent to.
    static final String OLD_BROWSER_PATH = "/plain/old";

    /// Message text accepted from one form post.
    private static final int MAX_TEXT_CHARS = 64 * 1024;

    /// How often the session page reloads itself while a reply is being written.
    private static final int AUTO_REFRESH_SECONDS = 5;

    private final @Nullable ChatService chat;
    private final AuthService auth;
    private final AuthFilter authFilter;
    private final boolean authDisabled;
    private final LoginThrottle throttle = new LoginThrottle();
    /// `""` at a root mount, otherwise `/panel` and the like.
    private final String basePath;

    public PlainChatServlet(@Nullable ChatService chat, AuthService auth, AuthFilter authFilter,
                            boolean authDisabled, @Nullable String basePath) {
        this.chat = chat;
        this.auth = auth;
        this.authFilter = authFilter;
        this.authDisabled = authDisabled;
        String normalized = basePath == null ? "" : basePath.trim();
        if ("/".equals(normalized)) {
            normalized = "";
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        this.basePath = normalized;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        request.setCharacterEncoding("UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/html; charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'");

        String path = request.getPathInfo() == null || request.getPathInfo().isEmpty()
                ? "/" : request.getPathInfo();
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        try {
            route(request, response, path);
        } catch (ChatException e) {
            error(response, e.status(), e.getMessage());
        } catch (SQLException e) {
            LOG.warning("Plain chat store failed", e);
            error(response, 500, "聊天数据库暂时不可用");
        }
    }

    private void route(HttpServletRequest request, HttpServletResponse response, String path)
            throws IOException, SQLException, ChatException {
        boolean post = "POST".equals(request.getMethod());

        if ("/old".equals(path)) {
            oldBrowser(response);
            return;
        }
        if ("/login".equals(path)) {
            if (post) {
                login(request, response);
            } else {
                loginPage(response, null);
            }
            return;
        }
        if ("/logout".equals(path)) {
            if (!post) {
                methodNotAllowed(response, "POST");
                return;
            }
            response.addCookie(authFilter.clearedSessionCookie(request));
            redirect(response, url("/plain"));
            return;
        }

        AuthService.Session user = user(request);
        if (user == null) {
            if (post) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            }
            loginPage(response, post ? "请先登录" : null);
            return;
        }

        if ("/".equals(path)) {
            home(response, user);
            return;
        }
        if ("/new".equals(path)) {
            if (!post) {
                methodNotAllowed(response, "POST");
                return;
            }
            if (!sameOrigin(request)) {
                error(response, 403, "跨站表单被拒绝");
                return;
            }
            newSession(request, response);
            return;
        }
        if (path.startsWith("/s/")) {
            String rest = path.substring(3);
            int slash = rest.indexOf('/');
            String sessionId = slash < 0 ? rest : rest.substring(0, slash);
            String action = slash < 0 ? "" : rest.substring(slash);
            if (sessionId.isEmpty()) {
                error(response, 404, "没有这个会话");
                return;
            }
            if (action.isEmpty()) {
                if (post) {
                    methodNotAllowed(response, "GET");
                    return;
                }
                sessionPage(request, response, sessionId);
                return;
            }
            if ("/send".equals(action)) {
                if (!post) {
                    methodNotAllowed(response, "POST");
                    return;
                }
                if (!sameOrigin(request)) {
                    error(response, 403, "跨站表单被拒绝");
                    return;
                }
                send(request, response, sessionId);
                return;
            }
        }

        error(response, 404, "这个地址不存在");
    }

    // ------------------------------------------------------------------ pages --

    private void loginPage(HttpServletResponse response, @Nullable String message) throws IOException {
        StringBuilder body = new StringBuilder();
        body.append("<h1>HDSL 面板 · 纯文本版</h1>\n");
        body.append("<p class=\"muted\">不需要 JavaScript，w3m / lynx 或旧浏览器都能用。</p>\n");
        if (message != null) {
            body.append("<p class=\"error\">").append(PlainHtml.escape(message)).append("</p>\n");
        }
        body.append("<form method=\"post\" action=\"").append(url("/plain/login")).append("\">\n")
                .append("<div class=\"row\"><label>用户名<br>")
                .append("<input type=\"text\" name=\"username\" autocomplete=\"username\" size=\"32\"></label></div>\n")
                .append("<div class=\"row\"><label>密码<br>")
                .append("<input type=\"password\" name=\"password\" autocomplete=\"current-password\" size=\"32\"></label></div>\n")
                .append("<div class=\"row\"><button type=\"submit\">登录</button></div>\n")
                .append("</form>\n")
                .append("<p class=\"muted\"><a href=\"").append(url("/")).append("\">用现代浏览器打开面板</a></p>\n");
        write(response, 200, PlainHtml.page("登录 · HDSL 纯文本版", null, body.toString()));
    }

    private void home(HttpServletResponse response, AuthService.Session user)
            throws IOException, SQLException {
        StringBuilder body = new StringBuilder();
        body.append("<nav><strong>HDSL 纯文本版</strong> ")
                .append("<a href=\"").append(url("/plain")).append("\">会话</a>")
                .append("<a href=\"").append(url("/")).append("\">完整面板</a>")
                .append("<form method=\"post\" action=\"").append(url("/plain/logout"))
                .append("\" style=\"display:inline\"><button type=\"submit\">退出</button></form>")
                .append("</nav>\n");
        body.append("<p class=\"muted\">登录为 ").append(PlainHtml.escape(user.username()))
                .append("；回答生成时可用手动刷新或开启自动刷新。</p>\n");

        if (chat == null) {
            body.append("<p class=\"error\">聊天功能在这个实例上不可用（数据库未能打开）。</p>\n");
            write(response, 200, PlainHtml.page("会话 · HDSL 纯文本版", null, body.toString()));
            return;
        }

        body.append("<h1>会话</h1>\n");
        JsonArray sessions = chat.sessions(null);
        if (sessions.isEmpty()) {
            body.append("<p class=\"muted\">还没有会话。先在下面新建一个。</p>\n");
        } else {
            body.append("<table><tr><th>标题</th><th>更新时间</th><th>消息</th></tr>\n");
            for (JsonElement element : sessions) {
                JsonObject session = element.getAsJsonObject();
                String id = string(session, "id");
                String title = string(session, "title");
                body.append("<tr><td><a href=\"").append(url("/plain/s/" + id)).append("\">")
                        .append(PlainHtml.escape(title.isBlank() ? "新会话" : title)).append("</a></td>")
                        .append("<td>").append(PlainHtml.time(longOf(session, "updatedAt"))).append("</td>")
                        .append("<td>").append(longOf(session, "messageCount")).append("</td></tr>\n");
            }
            body.append("</table>\n");
        }

        body.append("<h2>新建会话</h2>\n");
        JsonArray assistants = chat.assistants();
        if (assistants.isEmpty()) {
            body.append("<p class=\"muted\">没有可用的助手。</p>\n");
        } else {
            body.append("<form method=\"post\" action=\"").append(url("/plain/new")).append("\">\n")
                    .append("<div class=\"row\"><label>助手 <select name=\"assistantId\">\n");
            for (JsonElement element : assistants) {
                JsonObject assistant = element.getAsJsonObject();
                body.append("<option value=\"").append(PlainHtml.escape(string(assistant, "id")))
                        .append("\">").append(PlainHtml.escape(string(assistant, "name")))
                        .append("</option>\n");
            }
            body.append("</select></label></div>\n")
                    .append("<div class=\"row\"><button type=\"submit\">创建并打开</button></div>\n")
                    .append("</form>\n");
        }
        write(response, 200, PlainHtml.page("会话 · HDSL 纯文本版", null, body.toString()));
    }

    private void sessionPage(HttpServletRequest request, HttpServletResponse response, String sessionId)
            throws IOException, SQLException, ChatException {
        JsonObject session = requireChat().sessionJson(sessionId);
        boolean streaming = session.has("streaming") && session.get("streaming").getAsBoolean();
        boolean auto = "1".equals(request.getParameter("auto"));
        String error = request.getParameter("error");

        StringBuilder body = new StringBuilder();
        body.append("<nav><a href=\"").append(url("/plain")).append("\">← 会话列表</a>")
                .append("<a href=\"").append(url("/plain/s/" + sessionId)).append("\">刷新</a>")
                .append(auto
                        ? "<a href=\"" + url("/plain/s/" + sessionId) + "\">关闭自动刷新</a>"
                        : "<a href=\"" + url("/plain/s/" + sessionId) + "?auto=1\">自动刷新（"
                        + AUTO_REFRESH_SECONDS + " 秒）</a>")
                .append("<form method=\"post\" action=\"").append(url("/plain/logout"))
                .append("\" style=\"display:inline\"><button type=\"submit\">退出</button></form>")
                .append("</nav>\n");

        body.append("<h1>").append(PlainHtml.escape(string(session, "title"))).append("</h1>\n");
        String model = string(session, "model");
        body.append("<p class=\"muted\">模型 ")
                .append(PlainHtml.escape(model.isBlank() ? "默认" : model))
                .append(" · 助手 ").append(PlainHtml.escape(assistantName(string(session, "assistantId"))))
                .append(streaming ? " · <strong>正在生成回答…</strong>" : "")
                .append("</p>\n");

        if (error != null && !error.isBlank()) {
            body.append("<p class=\"error\">").append(PlainHtml.escape(errorText(error))).append("</p>\n");
        }

        JsonArray nodes = session.getAsJsonArray("nodes");
        for (JsonElement element : nodes) {
            JsonObject node = element.getAsJsonObject();
            JsonObject message = activeVariant(node);
            if (message == null) {
                continue;
            }
            boolean assistant = "assistant".equals(string(node, "role"));
            String status = string(message, "status");
            String content = string(message, "content");
            body.append("<div class=\"row\"><strong>").append(assistant ? "助手" : "你")
                    .append("</strong> <span class=\"muted\">")
                    .append(PlainHtml.time(longOf(message, "createdAt")))
                    .append(" · ").append(statusText(status, assistant)).append("</span></div>\n");
            if (content.isBlank() && "streaming".equals(status)) {
                body.append("<pre class=\"msg\">（正在生成…）</pre>\n");
            } else {
                body.append("<pre class=\"msg\">").append(PlainHtml.escape(content)).append("</pre>\n");
            }
            JsonArray attachments = message.has("attachments")
                    && message.get("attachments").isJsonArray()
                    ? message.getAsJsonArray("attachments") : new JsonArray();
            if (!attachments.isEmpty()) {
                body.append("<div>附件：");
                for (int i = 0; i < attachments.size(); i++) {
                    JsonObject attachment = attachments.get(i).getAsJsonObject();
                    String id = string(attachment, "id");
                    String name = string(attachment, "name");
                    if (i > 0) {
                        body.append(" · ");
                    }
                    body.append("<a href=\"").append(url("/api/chat/attachments/" + id))
                            .append("\">").append(PlainHtml.escape(name)).append("</a>");
                }
                body.append("</div>\n");
            }
        }

        body.append("<h2>发送消息</h2>\n")
                .append("<form method=\"post\" action=\"").append(url("/plain/s/" + sessionId + "/send"))
                .append("\">\n")
                .append("<textarea name=\"text\" rows=\"6\" cols=\"80\"></textarea>\n")
                .append("<input type=\"hidden\" name=\"auto\" value=\"1\">\n")
                .append("<div class=\"row\"><button type=\"submit\">发送</button> ")
                .append("<span class=\"muted\">提交后会自动回到本页；回答生成中请刷新查看。</span></div>\n")
                .append("</form>\n")
                .append("<p><a id=\"bottom\" href=\"").append(url("/plain/s/" + sessionId))
                .append("?auto=1\">刷新看结果</a></p>\n");

        String head = streaming && auto
                ? "<meta http-equiv=\"refresh\" content=\"" + AUTO_REFRESH_SECONDS + "\">"
                : null;
        write(response, 200, PlainHtml.page(string(session, "title") + " · HDSL 纯文本版",
                head, body.toString()));
    }

    private void oldBrowser(HttpServletResponse response) throws IOException {
        String body = "<h1>这个浏览器太旧了</h1>\n"
                + "<p>完整面板需要 JavaScript、fetch、WebSocket 和 CSS flex 布局。"
                + "当前浏览器缺少其中至少一项，所以没有加载那套界面——不是服务器挂了。</p>\n"
                + "<p>两个选择：</p>\n"
                + "<ul>"
                + "<li><a href=\"" + url("/plain") + "\"><strong>继续用纯文本版</strong></a>："
                + "登录、看会话、发消息、下载附件都能用，适合 w3m / lynx / 老机器。</li>"
                + "<li>换一个现代浏览器（Chrome / Edge / Firefox / Safari）打开 "
                + "<a href=\"" + url("/") + "\">完整面板</a>。</li>"
                + "</ul>\n"
                + "<p class=\"muted\">Windows 7 上如果还在用 IE，建议装 Firefox ESR 115 或 Chrome 109。</p>\n";
        write(response, 200, PlainHtml.page("浏览器太旧 · HDSL", null, body));
    }

    // ----------------------------------------------------------------- actions --

    private void login(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!sameOrigin(request)) {
            error(response, 403, "跨站表单被拒绝");
            return;
        }
        if (authDisabled) {
            redirect(response, url("/plain"));
            return;
        }
        String username = trimToNull(request.getParameter("username"));
        String password = request.getParameter("password");
        if (username == null || password == null || password.isEmpty()) {
            loginPage(response, "用户名和密码都要填");
            return;
        }

        String address = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        if (throttle.isBlocked(address, Instant.now())) {
            AuditLog.log(AuditLog.Channel.WEB, "login", null, "blocked", username, address);
            loginPage(response, "尝试次数过多，请 " + minutes(throttle.block()) + " 分钟后再试");
            return;
        }
        Optional<String> token = auth.login(username, password);
        if (token.isEmpty()) {
            Duration refused = throttle.recordFailure(address, Instant.now());
            AuditLog.log(AuditLog.Channel.WEB, "login", null, "failure", username, address);
            loginPage(response, refused.isZero() ? "用户名或密码不对"
                    : "尝试次数过多，请 " + minutes(refused) + " 分钟后再试");
            return;
        }
        throttle.clear(address);
        AuditLog.log(AuditLog.Channel.WEB, "login", null, "success", username, address);
        org.jackhuang.hmcl.web.system.SystemActivity.touch();
        response.addCookie(authFilter.sessionCookie(request, token.get()));
        redirect(response, url("/plain"));
    }

    private void newSession(HttpServletRequest request, HttpServletResponse response)
            throws IOException, SQLException, ChatException {
        ChatService service = requireChat();
        JsonObject body = new JsonObject();
        String assistantId = trimToNull(request.getParameter("assistantId"));
        body.addProperty("assistantId", assistantId == null ? "default" : assistantId);
        JsonObject session = service.createSession(body);
        redirect(response, url("/plain/s/" + string(session, "id")));
    }

    private void send(HttpServletRequest request, HttpServletResponse response, String sessionId)
            throws IOException, SQLException, ChatException {
        String text = request.getParameter("text");
        if (text == null || text.isBlank()) {
            redirect(response, url("/plain/s/" + sessionId + "?error=empty"));
            return;
        }
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
        }
        try {
            requireChat().turns().send(sessionId, text.strip(), List.of(), null, null, null, false);
        } catch (ChatException e) {
            if ("chat_busy".equals(e.code())) {
                redirect(response, url("/plain/s/" + sessionId + "?error=busy"));
                return;
            }
            throw e;
        }
        redirect(response, url("/plain/s/" + sessionId + "?auto=1"));
    }

    // ------------------------------------------------------------------ helpers --

    private ChatService requireChat() throws ChatException {
        if (chat == null) {
            throw new ChatException(503, "chat_unavailable", "聊天功能在这个实例上不可用");
        }
        return chat;
    }

    /// The assistant's display name, or the id when it is unknown.
    private String assistantName(String assistantId) throws SQLException {
        if (chat == null) {
            return assistantId;
        }
        for (JsonElement element : chat.assistants()) {
            JsonObject assistant = element.getAsJsonObject();
            if (assistantId.equals(string(assistant, "id"))) {
                return string(assistant, "name");
            }
        }
        return assistantId;
    }

    private @Nullable AuthService.Session user(HttpServletRequest request) {
        if (authDisabled) {
            return new AuthService.Session("admin", Instant.MAX);
        }
        String token = cookie(request, AuthFilter.SESSION_COOKIE);
        return token == null ? null : auth.verify(token).orElse(null);
    }

    private static @Nullable String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie candidate : cookies) {
            if (name.equals(candidate.getName())) {
                return candidate.getValue();
            }
        }
        return null;
    }

    /// Whether a form post came from this origin.
    ///
    /// The session cookie is `SameSite=Strict`, so a cross-site form does not
    /// carry it; this is the second lock, and the one that also covers a browser
    /// that ignores SameSite. A request with neither header (w3m, curl) is let
    /// through: those clients only post what a person typed.
    private static boolean sameOrigin(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host == null || host.isBlank()) {
            return false;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            if ("null".equals(origin)) {
                return false;
            }
            return matchesHost(origin, host);
        }
        String referer = request.getHeader("Referer");
        return referer == null || referer.isBlank() || matchesHost(referer, host);
    }

    private static boolean matchesHost(String url, String host) {
        try {
            String authority = URI.create(url).getRawAuthority();
            return authority != null && host.equalsIgnoreCase(authority);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String url(String path) {
        return basePath + path;
    }

    private static void redirect(HttpServletResponse response, String location) {
        response.setStatus(HttpServletResponse.SC_SEE_OTHER);
        response.setHeader("Location", location);
    }

    private static void write(HttpServletResponse response, int status, String html) throws IOException {
        response.setStatus(status);
        response.getWriter().write(html);
    }

    private void error(HttpServletResponse response, int status, @Nullable String message) throws IOException {
        String body = "<h1>出错了</h1>\n<p class=\"error\">"
                + PlainHtml.escape(message == null ? "请求失败" : message) + "</p>\n"
                + "<p><a href=\"" + url("/plain") + "\">返回会话列表</a></p>\n";
        write(response, status, PlainHtml.page("出错了 · HDSL 纯文本版", null, body));
    }

    private static void methodNotAllowed(HttpServletResponse response, String allowed) throws IOException {
        response.setHeader("Allow", allowed);
        StringBuilder body = new StringBuilder("<h1>不支持这个请求方式</h1>\n")
                .append("<p>这里只接受 ").append(PlainHtml.escape(allowed)).append("。</p>\n");
        write(response, 405, PlainHtml.page("405 · HDSL 纯文本版", null, body.toString()));
    }

    private static String errorText(String code) {
        return switch (code) {
            case "empty" -> "消息不能为空";
            case "busy" -> "上一条回答还在生成，等它结束再发（或者先刷新看看）";
            default -> code;
        };
    }

    private static String statusText(String status, boolean assistant) {
        if (!assistant) {
            return "已发送";
        }
        return switch (status) {
            case "streaming" -> "生成中";
            case "error" -> "生成失败";
            case "stopped" -> "已停止";
            default -> "完成";
        };
    }

    private static @Nullable JsonObject activeVariant(JsonObject node) {
        JsonArray variants = node.has("variants") && node.get("variants").isJsonArray()
                ? node.getAsJsonArray("variants") : new JsonArray();
        if (variants.isEmpty()) {
            return null;
        }
        String active = string(node, "activeVariantId");
        for (JsonElement element : variants) {
            JsonObject message = element.getAsJsonObject();
            if (string(message, "id").equals(active)) {
                return message;
            }
        }
        return variants.get(0).getAsJsonObject();
    }

    private static String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static long longOf(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsLong() : 0L;
    }

    private static @Nullable String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long minutes(Duration duration) {
        return Math.max(1, duration.toMinutes());
    }
}