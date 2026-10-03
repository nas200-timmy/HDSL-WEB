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
package org.jackhuang.hmcl.web.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jackhuang.hmcl.web.auth.AuthFilter;
import org.jackhuang.hmcl.web.auth.AuthService;
import org.jackhuang.hmcl.web.auth.UserStore;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/// The whole Phase 1 REST surface, mapped at `/api/*`:
///
/// - `GET  /api/health`        — liveness, public
/// - `POST /api/auth/login`    — credentials in, session cookie out, public
/// - `GET  /api/auth/status`   — first-run flag (`setupRequired`), public
/// - `POST /api/auth/setup`    — creates the first user, session cookie out,
///                               public but refuses to run twice (409)
/// - `POST /api/auth/password` — password change, revokes the user's other
///                               sessions, needs a session
/// - `POST /api/auth/logout`   — clears the cookie and the session
/// - `GET  /api/auth/whoami`   — the session's username
///
/// Phase 2 grows this into a router with a handler per resource; for seven
/// endpoints a switch is the honest size.
@NotNullByDefault
public final class ApiServlet extends HttpServlet {

    private final AuthService authService;
    private final AuthFilter authFilter;
    private final boolean authDisabled;
    private final String version;
    private final LongSupplier uptimeStartMillis;

    public ApiServlet(AuthService authService, AuthFilter authFilter, boolean authDisabled,
                      String version, LongSupplier uptimeStartMillis) {
        this.authService = authService;
        this.authFilter = authFilter;
        this.authDisabled = authDisabled;
        this.version = version;
        this.uptimeStartMillis = uptimeStartMillis;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo() == null ? "/" : request.getPathInfo();
        String method = request.getMethod();

        if ("/health".equals(path) && "GET".equals(method)) {
            health(response);
        } else if ("/auth/login".equals(path) && "POST".equals(method)) {
            login(request, response);
        } else if ("/auth/status".equals(path) && "GET".equals(method)) {
            status(response);
        } else if ("/auth/setup".equals(path) && "POST".equals(method)) {
            setup(request, response);
        } else if ("/auth/password".equals(path) && "POST".equals(method)) {
            password(request, response);
        } else if ("/auth/logout".equals(path) && "POST".equals(method)) {
            logout(request, response);
        } else if ("/auth/whoami".equals(path) && "GET".equals(method)) {
            whoami(request, response);
        } else {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
        }
    }

    private void health(HttpServletResponse response) throws IOException {
        long uptimeSec = Math.max(0, (System.currentTimeMillis() - uptimeStartMillis.getAsLong()) / 1000);
        Json.write(response, Map.of("status", "ok", "version", version, "uptimeSec", uptimeSec));
    }

    private void login(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject body;
        try {
            body = JsonParser.parseString(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must be a JSON object");
            return;
        }
        String username = stringField(body, "username");
        String password = stringField(body, "password");
        if (username == null || password == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "username and password are required");
            return;
        }

        Optional<String> token = authService.login(username, password);
        if (token.isEmpty()) {
            Json.error(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid username or password");
            return;
        }
        response.addCookie(authFilter.sessionCookie(token.get()));
        Json.write(response, new LoginBody(true, username));
    }

    private void status(HttpServletResponse response) throws IOException {
        Json.write(response, Map.of("setupRequired", !authService.userStore().initialized()));
    }

    private void setup(HttpServletRequest request, HttpServletResponse response) throws IOException {
        UserStore users = authService.userStore();
        if (users.initialized()) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, "already initialized");
            return;
        }
        JsonObject body;
        try {
            body = JsonParser.parseString(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must be a JSON object");
            return;
        }
        String username = stringField(body, "username");
        String password = stringField(body, "password");
        if (!UserStore.isValidUsername(username) || !UserStore.isValidPassword(password)) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "username must be 1-32 characters without whitespace and password at least "
                            + UserStore.PASSWORD_MIN_LENGTH + " characters");
            return;
        }

        try {
            users.createUser(username, password);
        } catch (IllegalStateException race) {
            // A concurrent setup won the race after the check above.
            Json.error(response, HttpServletResponse.SC_CONFLICT, "already initialized");
            return;
        }
        Optional<String> token = authService.createSession(username);
        if (token.isEmpty()) {
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "failed to open a session");
            return;
        }
        response.addCookie(authFilter.sessionCookie(token.get()));
        Json.write(response, HttpServletResponse.SC_CREATED, new SetupBody(true, username));
    }

    private void password(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object session = request.getAttribute(AuthFilter.SESSION_ATTRIBUTE);
        if (!(session instanceof AuthService.Session s)) {
            // The filter already 401s without a session when auth is on; a
            // reachable request with no attribute means auth is off, where a
            // password change has nothing to protect.
            Json.error(response, authDisabled
                    ? HttpServletResponse.SC_BAD_REQUEST
                    : HttpServletResponse.SC_UNAUTHORIZED, authDisabled ? "authentication is disabled" : "unauthorized");
            return;
        }

        JsonObject body;
        try {
            body = JsonParser.parseString(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must be a JSON object");
            return;
        }
        String currentPassword = stringField(body, "currentPassword");
        String newPassword = stringField(body, "newPassword");
        if (currentPassword == null || newPassword == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "currentPassword and newPassword are required");
            return;
        }
        if (!UserStore.isValidPassword(newPassword)) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "password must be at least " + UserStore.PASSWORD_MIN_LENGTH + " characters");
            return;
        }

        UserStore users = authService.userStore();
        if (!users.verify(s.username(), currentPassword)) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN, "current password is incorrect");
            return;
        }
        if (!users.updatePassword(s.username(), newPassword)) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, "user no longer exists");
            return;
        }
        // The session that made the change survives; every other session of
        // this user (other browsers, other devices) is logged out.
        authService.revokeOtherSessions(s.username(), cookieValue(request, AuthFilter.SESSION_COOKIE));
        Json.write(response, Map.of("ok", true));
    }

    private void logout(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String token = cookieValue(request, AuthFilter.SESSION_COOKIE);
        authService.logout(token);
        response.addCookie(authFilter.clearedSessionCookie());
        Json.write(response, Map.of("ok", true));
    }

    private void whoami(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object session = request.getAttribute(AuthFilter.SESSION_ATTRIBUTE);
        if (session instanceof AuthService.Session s) {
            Json.write(response, new WhoamiBody(s.username()));
        } else if (authDisabled) {
            // Without a session there is no username to report; with auth
            // switched off the single admin is the answer.
            Json.write(response, new WhoamiBody("admin"));
        } else {
            Json.error(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized");
        }
    }

    private static @Nullable String stringField(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : null;
    }

    private static @Nullable String cookieValue(HttpServletRequest request, String name) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (var cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private record LoginBody(boolean ok, String username) {
    }

    private record SetupBody(boolean ok, String username) {
    }

    private record WhoamiBody(String username) {
    }
}
