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
package org.jackhuang.hmcl.web.developer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jackhuang.hmcl.web.auth.AuthFilter;
import org.jackhuang.hmcl.web.auth.AuthService;
import org.jackhuang.hmcl.web.auth.LoginThrottle;
import org.jackhuang.hmcl.web.auth.UserStore;
import org.jackhuang.hmcl.web.http.Json;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/// The developer mode's own door: the second factor, independent of whether
/// the SSH channel is in this build.
///
/// `POST /api/developer/elevate` proves the password; `GET /api/developer/status`
/// answers whether the caller is elevated; `POST /api/developer/password` sets
/// or changes it (panel password confirmation, like the SSH settings route);
/// `POST /api/developer/password/clear` removes it.
///
/// It lives outside the SSH package on purpose: the web terminal needs the
/// same second factor, and the default build carries this endpoint. The SSH
/// settings servlet keeps `/api/ssh/elevate` and the password routes for
/// compatibility, backed by the same [DeveloperElevation] and [DeveloperAuth].
@NotNullByDefault
public final class DeveloperApiServlet extends HttpServlet {

    private final DeveloperAuth devAuth;
    private final DeveloperElevation elevation;
    private final AuthService auth;
    private final BooleanSupplier tlsActive;
    /// Failed panel-password confirmations, per source address — the second
    /// confirmation must not become an unthrottled oracle for the panel
    /// password.
    private final LoginThrottle adminPasswordThrottle = new LoginThrottle();

    public DeveloperApiServlet(DeveloperAuth devAuth, DeveloperElevation elevation,
                               AuthService auth, BooleanSupplier tlsActive) {
        this.devAuth = devAuth;
        this.elevation = elevation;
        this.auth = auth;
        this.tlsActive = tlsActive;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        String method = request.getMethod();
        if ("/status".equals(path) && "GET".equals(method)) {
            JsonObject body = new JsonObject();
            body.addProperty("devPasswordSet", devAuth.isSet());
            body.addProperty("elevated", elevation.isElevated(keyOf(request), sessionAlive(request)));
            Json.write(response, body);
            return;
        }
        if ("/elevate".equals(path) && "POST".equals(method)) {
            elevate(request, response);
            return;
        }
        if ("/password".equals(path) && "POST".equals(method)) {
            setPassword(request, response);
            return;
        }
        if ("/password/clear".equals(path) && "POST".equals(method)) {
            clearPassword(request, response);
            return;
        }
        Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
    }

    private void elevate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireTls(response)) {
            return;
        }
        String password = stringField(readJson(request), "password");
        if (password == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "password is required");
            return;
        }
        String key = keyOf(request);
        Instant now = Instant.now();
        if (elevation.isLocked(key, now)) {
            Json.error(response, 429, "too many failed attempts; this session is locked for "
                    + DeveloperElevation.LOCK.toMinutes() + " minutes");
            return;
        }
        if (!devAuth.isSet()) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "developer_password_not_set: set it before opening a terminal");
            return;
        }
        if (!devAuth.verify(password)) {
            boolean locked = elevation.recordFailure(key, now);
            if (locked) {
                Json.error(response, 429, "too many failed attempts; this session is locked for "
                        + DeveloperElevation.LOCK.toMinutes() + " minutes");
            } else {
                Json.error(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid developer password");
            }
            return;
        }
        elevation.elevate(key);
        elevation.clearFailures(key);
        JsonObject body = new JsonObject();
        body.addProperty("elevated", true);
        body.addProperty("expiresAt", now.plus(DeveloperElevation.TTL).toString());
        Json.write(response, body);
    }

    private void setPassword(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireTls(response)) {
            return;
        }
        JsonObject body = readJson(request);
        String adminPassword = stringField(body, "adminPassword");
        String password = stringField(body, "password");
        String confirm = stringField(body, "confirm");
        String current = stringField(body, "currentDevPassword");
        if (adminPassword == null || password == null || confirm == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "adminPassword, password and confirm are required");
            return;
        }
        UserStore users = auth.userStore();
        if (!confirmPanelPassword(request, response, users, adminPassword)) {
            return;
        }
        boolean changing = devAuth.isSet();
        if (changing && (current == null || !devAuth.verify(current))) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN,
                    "the current developer password does not match");
            return;
        }
        if (!password.equals(confirm)) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "password and confirm do not match");
            return;
        }
        try {
            devAuth.set(password, users);
        } catch (IllegalArgumentException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "the developer password could not be stored: " + e.getMessage());
            return;
        }
        // A new secret means every elevation minted against the old one dies.
        elevation.clearAll();
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", true);
        answer.addProperty("devPasswordSet", true);
        Json.write(response, answer);
    }

    private void clearPassword(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireTls(response)) {
            return;
        }
        String adminPassword = stringField(readJson(request), "adminPassword");
        if (adminPassword == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "adminPassword is required");
            return;
        }
        if (!confirmPanelPassword(request, response, auth.userStore(), adminPassword)) {
            return;
        }
        try {
            devAuth.clear();
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "the developer password could not be cleared: " + e.getMessage());
            return;
        }
        elevation.clearAll();
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", true);
        answer.addProperty("devPasswordSet", false);
        Json.write(response, answer);
    }

    /// Confirms the panel password with a per-address budget, answering the
    /// refusal itself.
    private boolean confirmPanelPassword(HttpServletRequest request, HttpServletResponse response,
                                         UserStore users, String adminPassword) throws IOException {
        String address = request.getRemoteAddr();
        String key = address == null || address.isBlank() ? "unknown" : address;
        Instant now = Instant.now();
        if (adminPasswordThrottle.isBlocked(key, now)) {
            refuse(response, adminPasswordThrottle.block());
            return false;
        }
        UserStore.User admin = users.admin();
        if (admin != null && users.verify(admin.username(), adminPassword)) {
            adminPasswordThrottle.clear(key);
            return true;
        }
        Duration refused = adminPasswordThrottle.recordFailure(key, Instant.now());
        if (refused.isZero()) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN, "the panel password does not match");
        } else {
            refuse(response, refused);
        }
        return false;
    }

    private static void refuse(HttpServletResponse response, Duration refusedFor) throws IOException {
        response.setHeader("Retry-After", Long.toString(Math.max(1, refusedFor.toSeconds())));
        Json.error(response, 429, "too many failed panel-password attempts; try again later");
    }

    private boolean requireTls(HttpServletResponse response) throws IOException {
        if (tlsActive.getAsBoolean()) {
            return true;
        }
        Json.error(response, HttpServletResponse.SC_CONFLICT,
                "tls_required: the developer password is only accepted over HTTPS");
        return false;
    }

    private static @Nullable JsonObject readJson(HttpServletRequest request) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable String stringField(@Nullable JsonObject body, String field) {
        return body != null && body.has(field) && !body.get(field).isJsonNull()
                ? body.get(field).getAsString()
                : null;
    }

    private boolean sessionAlive(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return !(token instanceof String value) || value.isBlank() || auth.isAlive(value);
    }

    private static String keyOf(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return DeveloperElevation.key(token instanceof String value ? value : null, request.getRemoteAddr());
    }
}
